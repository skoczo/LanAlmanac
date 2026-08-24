package com.gnm.fingerprint;

import org.junit.jupiter.api.Test;
import java.util.List;
import com.gnm.model.FingerprintVector;

import static org.junit.jupiter.api.Assertions.*;

public class SimilarityEngineTest {

    private final SimilarityEngine engine = new SimilarityEngine();

    @Test
    public void testExactMatch() {
        FingerprintVector v1 = new FingerprintVector();
        v1.dhcpOption55 = "1,3,6,15,119,252";
        v1.dhcpOption60 = "AppleTV";
        v1.tcpFingerprint = "64:14600:1:0";
        v1.mdnsServices = List.of("_airplay._tcp", "_raop._tcp");
        v1.openPorts = List.of(80, 443, 7000);
        v1.macOui = "AA:BB:CC";

        FingerprintVector v2 = new FingerprintVector();
        v2.dhcpOption55 = "1,3,6,15,119,252";
        v2.dhcpOption60 = "AppleTV";
        v2.tcpFingerprint = "64:14600:1:0";
        v2.mdnsServices = List.of("_airplay._tcp", "_raop._tcp");
        v2.openPorts = List.of(80, 443, 7000);
        v2.macOui = "AA:BB:CC";

        SimilarityEngine.SimilarityResult result = engine.calculateSimilarity(v1, v2);
        assertEquals(1.0, result.score, 0.0001, "Exact same vectors must return 1.0 similarity");
    }

    @Test
    public void testPartialMatchWithMacRandomization() {
        // Candidate vector (e.g. captured when MAC randomization changed the IP and OUI)
        FingerprintVector candidate = new FingerprintVector();
        candidate.dhcpOption55 = "1,3,6,15,119,252"; // Same iOS parameter request list
        candidate.mdnsServices = List.of("_airplay._tcp", "_companion-link._tcp"); // Same mDNS ads
        candidate.macOui = "11:22:33"; // Different MAC OUI due to randomization

        // Historical vector
        FingerprintVector historical = new FingerprintVector();
        historical.dhcpOption55 = "1,3,6,15,119,252";
        historical.mdnsServices = List.of("_airplay._tcp", "_companion-link._tcp");
        historical.macOui = "AA:BB:CC"; // Original MAC OUI

        SimilarityEngine.SimilarityResult result = engine.calculateSimilarity(candidate, historical);
        
        // Weight sum = DHCP_55 (0.3) + MDNS (0.3) + MAC_OUI (0.05) = 0.65
        // Score sum = 1.0*0.3 + 1.0*0.3 + 0.0*0.05 = 0.6
        // Similarity = 0.6 / 0.65 = 0.923
        assertTrue(result.score >= 0.75, "Partial match under MAC randomization should exceed merge threshold (score: " + result.score + ")");
        assertEquals(0.923, result.score, 0.001);
    }

    @Test
    public void testCompleteMismatch() {
        // v1 (iPhone)
        FingerprintVector v1 = new FingerprintVector();
        v1.dhcpOption55 = "1,3,6,15,119,252";
        v1.dhcpOption60 = "AppleDevice";
        v1.mdnsServices = List.of("_airplay._tcp");

        // v2 (HP Printer)
        FingerprintVector v2 = new FingerprintVector();
        v2.dhcpOption55 = "1,3,6,15";
        v2.dhcpOption60 = "HP-LaserJet";
        v2.mdnsServices = List.of("_ipp._tcp", "_printer._tcp");

        SimilarityEngine.SimilarityResult result = engine.calculateSimilarity(v1, v2);
        assertTrue(result.score < 0.35, "Different categories must return low similarity (score: " + result.score + ")");
    }

    @Test
    public void testNullAndEmptyHandling() {
        SimilarityEngine.SimilarityResult resultNull = engine.calculateSimilarity(null, null);
        assertEquals(0.0, resultNull.score);

        FingerprintVector empty = new FingerprintVector();
        SimilarityEngine.SimilarityResult resultEmpty = engine.calculateSimilarity(empty, empty);
        assertEquals(0.0, resultEmpty.score, "Empty vectors should yield 0.0 as there are no signals to compare");
    }
}
