package com.gnm.discovery;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
public class ArpScannerTest {

    @Inject
    ArpScanner arpScanner;

    @Test
    public void testArpScanExecutesWithFallbackOrNative() {
        // When: scan() is invoked
        Set<String> discoveredIps = arpScanner.scan();

        // Then: Result should not be null, either running native ARP scan or failing gracefully to fallback
        assertNotNull(discoveredIps, "ARP scanner should return a non-null set of IP addresses");
    }
}
