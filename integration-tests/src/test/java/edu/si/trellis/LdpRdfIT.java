package edu.si.trellis;

import java.util.Collections;
import java.util.Set;

import org.trellisldp.test.LdpRdfTests;

class LdpRdfIT extends IT implements LdpRdfTests {

    @Override
    public void testPatchRDF() {
        // TODO waiting for https://github.com/trellis-ldp/trellis/issues/301
    }

    @Override
    public Set<String> supportedJsonLdProfiles() {
        return Collections.emptySet();
    }
}
