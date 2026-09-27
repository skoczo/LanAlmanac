package com.gnm.fingerprint;

import com.gnm.fingerprint.probes.*;
import com.gnm.model.FingerprintVector;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class ProbesTest {

    @Test
    public void testProbeContext() {
        FingerprintVector vector = new FingerprintVector();
        ProbeContext ctx = new ProbeContext("127.0.0.1", vector);

        assertEquals("127.0.0.1", ctx.getIpAddress());
        assertEquals(vector, ctx.getCandidate());
        assertNull(ctx.getResolvedHostname());

        ctx.setResolvedHostname("test-host.local");
        assertEquals("test-host.local", ctx.getResolvedHostname());

        assertThrows(IllegalStateException.class, () -> ctx.setResolvedHostname("other-host.local"));
    }

}
