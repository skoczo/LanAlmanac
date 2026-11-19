package com.gnm.pkgmgmt.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PkgWorkerHmacSignerTest {

    private static final byte[] TEST_SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void rejectsShortOrNullSecret() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PkgWorkerHmacSigner.computeSignature(null, "GET", "/v1/health", "1700000000", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> PkgWorkerHmacSigner.computeSignature(
                        "short-secret".getBytes(StandardCharsets.UTF_8),
                        "GET",
                        "/v1/health",
                        "1700000000",
                        null));
    }

    @Test
    void computesDeterministicLowercaseHexSignatureMatchingGoWorker() {
        byte[] body = "{\"ping\":true}".getBytes(StandardCharsets.UTF_8);
        String sig1 =
                PkgWorkerHmacSigner.computeSignature(
                        TEST_SECRET, "POST", "/v1/probe", "1700000000", body);
        String sig2 =
                PkgWorkerHmacSigner.computeSignature(
                        TEST_SECRET, "POST", "/v1/probe", "1700000000", body);

        assertEquals(64, sig1.length());
        assertEquals(sig1, sig2);
        assertTrue(sig1.matches("^[0-9a-f]{64}$"));

        String differentBodySig =
                PkgWorkerHmacSigner.computeSignature(
                        TEST_SECRET,
                        "POST",
                        "/v1/probe",
                        "1700000000",
                        "{\"ping\":false}".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(sig1, differentBodySig);
    }
}

