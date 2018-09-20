package edu.si.trellis.cassandra;

import static com.datastax.driver.core.ConsistencyLevel.LOCAL_ONE;
import static com.datastax.driver.core.ConsistencyLevel.LOCAL_QUORUM;
import static java.lang.Math.floorDiv;
import static java.util.Base64.getEncoder;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.stream.StreamSupport.stream;
import static org.apache.commons.codec.digest.DigestUtils.getDigest;
import static org.apache.commons.codec.digest.DigestUtils.updateDigest;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.MD2;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.MD5;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_1;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_256;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_384;
import static org.apache.commons.codec.digest.MessageDigestAlgorithms.SHA_512;
import static org.slf4j.LoggerFactory.getLogger;

import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;

import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.rdf.api.IRI;
import org.slf4j.Logger;
import org.trellisldp.api.BinaryService;
import org.trellisldp.api.IdentifierService;
import org.trellisldp.api.RuntimeTrellisException;

/**
 * Implements {@link BinaryService} by chunking binary data across Cassandra.
 *
 */
public class CassandraBinaryService implements BinaryService {

    private static final Logger log = getLogger(CassandraBinaryService.class);

    private final Session cassandraSession;

    private static final int DEFAULT_MAX_CHUNK_LENGTH = 1 * 1024 * 1024;

    private static final long DONE = -1;

    private int maxChunkLength = 1 * 1024 * 1024;

    private static final String SHA = "SHA";

    // TODO JDK9 supports SHA3 algorithms (SHA3_256, SHA3_384, SHA3_512)
    // TODO Move digest calculation to the C* node.
    private static final Set<String> algorithms = ImmutableSet.of(MD5, MD2, SHA, SHA_1, SHA_256, SHA_384, SHA_512);

    private static final String INSERT_QUERY = "INSERT INTO Binarydata (identifier, chunk_index, chunk) VALUES (:identifier, :chunk_index, :chunk)";

    private final PreparedStatement insertStatement;

    private static final String READ_QUERY = "SELECT chunk, chunk_index FROM Binarydata WHERE identifier = ?;";

    private final PreparedStatement readStatement;

    private static final String READ_RANGE_QUERY = "SELECT chunk, chunk_index FROM Binarydata WHERE identifier = ? and chunk_index >= :start and chunk_index <= :end;";

    private final PreparedStatement readRangeStatement;

    private static final String DELETE_QUERY = "DELETE FROM Binarydata WHERE identifier = ?;";

    private final PreparedStatement deleteStatement;

    private final IdentifierService idService;

    public CassandraBinaryService(IdentifierService idService, Session session, int chunkLength) {
        this.idService = idService;
        this.cassandraSession = session;
        this.maxChunkLength = chunkLength;
        insertStatement = session.prepare(INSERT_QUERY);
        readStatement = session.prepare(READ_QUERY);
        readRangeStatement = session.prepare(READ_RANGE_QUERY);
        deleteStatement = session.prepare(DELETE_QUERY);
    }

    @Inject
    public CassandraBinaryService(IdentifierService idService, Session session) {
        this(idService, session, DEFAULT_MAX_CHUNK_LENGTH);
    }

    @Override
    public CompletableFuture<InputStream> getContent(IRI identifier, Integer from, Integer to) {
        requireNonNull(from, "Byte range component 'from' may not be null!");
        requireNonNull(to, "Byte range component 'to' may not be null!");
        long firstChunk = floorDiv((long) from, maxChunkLength);
        long lastChunk = floorDiv((long) to, maxChunkLength);
        long chunkStreamStart = from % maxChunkLength;
        long rangeSize = to - from + 1; // +1 because range is inclusive
        Statement boundStatement = readRangeStatement.bind(identifier.getIRIString(), firstChunk, lastChunk)
                        .setConsistencyLevel(LOCAL_ONE);
        return retrieve(boundStatement).thenApply(in -> { // skip to fulfill lower end of range
            try {
                in.skip(chunkStreamStart);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return in;
        }).thenApply(in -> new BoundedInputStream(in, rangeSize)); // apply limit for upper end of range
    }

    @Override
    public CompletableFuture<InputStream> getContent(IRI identifier) {
        Statement boundStatement = readStatement.bind(identifier.getIRIString()).setConsistencyLevel(LOCAL_ONE);
        return retrieve(boundStatement);
    }

    private CompletableFuture<InputStream> retrieve(Statement boundStatement) {
        ResultSetFuture results = cassandraSession.executeAsync(boundStatement);
        return translate(results).thenApply(resultSet -> stream(resultSet.spliterator(), false)
                        .peek(r -> log.debug("Retrieving chunk: {}", r.getLong("chunk_index")))
                        .map(r -> r.get("chunk", InputStream.class))
                        .reduce(SequenceInputStream::new).get()); // chunks now in one large stream
    }

    @Override
    public CompletableFuture<Void> setContent(IRI iri, InputStream stream, Map<String, String> metadata /* ignored */) {
        return setChunk(iri, stream, new AtomicLong()).thenAccept(x -> {});
    }

    private CompletableFuture<Long> setChunk(IRI iri, InputStream stream, AtomicLong chunkIndex) {
        try (CountingInputStream chunk = new CountingInputStream(new BoundedInputStream(stream, maxChunkLength))) {
            Statement boundStatement = insertStatement.bind(iri, chunkIndex.getAndIncrement(), chunk)
                            .setConsistencyLevel(LOCAL_QUORUM);
            return translate(cassandraSession.executeAsync(boundStatement)).thenApply(r -> chunk.getByteCount())
                            .thenComposeAsync(bytesStored -> bytesStored == maxChunkLength
                                            ? setChunk(iri, stream, chunkIndex)
                                            : completedFuture(DONE), translator);
        }
    }

    @Override
    public CompletableFuture<Void> purgeContent(IRI identifier) {
        Statement boundStatement = deleteStatement.bind(identifier.getIRIString());
        return translate(cassandraSession.executeAsync(boundStatement)).thenAccept(x -> {});
    }

    @Override
    public CompletableFuture<String> calculateDigest(IRI identifier, String algorithm) {
        MessageDigest digest = getDigest(algorithm);
        return getContent(identifier).thenApplyAsync(in -> {
            try (InputStream stream = in) {
                return getEncoder().encodeToString(updateDigest(digest, stream).digest());
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }, translator);
    }

    @Override
    public Set<String> supportedAlgorithms() {
        return algorithms;
    }

    @Override
    public String generateIdentifier() {
        return idService.getSupplier().get();
    }

    private Executor translator = Runnable::run;

    private <T> CompletableFuture<T> translate(ListenableFuture<T> from) {
        return supplyAsync(() -> {
            try {
                return from.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeTrellisException(e);
            }
        }, translator);
    }

    /**
     * An {@link InputStream} that counts the bytes read from it and does not propagate {@link #close()}.
     *
     */
    private static class CountingInputStream extends org.apache.commons.io.input.CountingInputStream {

        public CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() { /* NO OP */ }
    }
}