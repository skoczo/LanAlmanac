package com.gnm.fingerprint.probes;

import com.gnm.model.FingerprintVector;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class ProbeContextTest {

    @Test
    public void testProbeContextGettersAndSetters() {
        FingerprintVector vector = new FingerprintVector();
        ProbeContext ctx = new ProbeContext("192.168.1.10", vector);

        assertEquals("192.168.1.10", ctx.getIpAddress());
        assertEquals(vector, ctx.getCandidate());
        assertNull(ctx.getResolvedHostname());

        ctx.setResolvedHostname("gateway.local");
        assertEquals("gateway.local", ctx.getResolvedHostname());
    }
}
