package com.gnm.fingerprint;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.gnm.model.FingerprintVector;

@ApplicationScoped
public class SimilarityEngine {

    // Define static weights for each signal type
    private static final double W_DHCP_55 = 0.30;
    private static final double W_DHCP_60 = 0.15;
    private static final double W_TCP = 0.20;
    private static final double W_MDNS = 0.30;
    private static final double W_OPEN_PORTS = 0.10;
    private static final double W_MAC_OUI = 0.05;
    private static final double W_SSH = 0.40;
    private static final double W_HTTP = 0.25;
    private static final double W_TLS_JA4 = 0.35;
    private static final double W_TLS_CERT = 0.35;

    public double calculateSimilarity(FingerprintVector candidate, FingerprintVector historical) {
        if (candidate == null || historical == null) {
            return 0.0;
        }

        double weightedScoreSum = 0.0;
        double weightSum = 0.0;

        // 1. DHCP Option 55
        if (hasValue(candidate.dhcpOption55) || hasValue(historical.dhcpOption55)) {
            double score = compareStrings(candidate.dhcpOption55, historical.dhcpOption55);
            weightedScoreSum += score * W_DHCP_55;
            weightSum += W_DHCP_55;
        }

        // 2. DHCP Option 60
        if (hasValue(candidate.dhcpOption60) || hasValue(historical.dhcpOption60)) {
            double score = compareStrings(candidate.dhcpOption60, historical.dhcpOption60);
            weightedScoreSum += score * W_DHCP_60;
            weightSum += W_DHCP_60;
        }

        // 3. TCP Fingerprint
        if (hasValue(candidate.tcpFingerprint) || hasValue(historical.tcpFingerprint)) {
            double score = compareStrings(candidate.tcpFingerprint, historical.tcpFingerprint);
            weightedScoreSum += score * W_TCP;
            weightSum += W_TCP;
        }

        // 4. mDNS Services (Jaccard Index)
        if (hasList(candidate.mdnsServices) || hasList(historical.mdnsServices)) {
            double score = compareLists(candidate.mdnsServices, historical.mdnsServices);
            weightedScoreSum += score * W_MDNS;
            weightSum += W_MDNS;
        }

        // 5. Open Ports (Jaccard Index)
        if (hasList(candidate.openPorts) || hasList(historical.openPorts)) {
            double score = compareLists(candidate.openPorts, historical.openPorts);
            weightedScoreSum += score * W_OPEN_PORTS;
            weightSum += W_OPEN_PORTS;
        }

        // 6. MAC OUI
        if (hasValue(candidate.macOui) || hasValue(historical.macOui)) {
            double score = compareStrings(candidate.macOui, historical.macOui);
            weightedScoreSum += score * W_MAC_OUI;
            weightSum += W_MAC_OUI;
        }

        // 7. SSH Banner
        if (hasValue(candidate.sshBanner) || hasValue(historical.sshBanner)) {
            double score = compareStringsContains(candidate.sshBanner, historical.sshBanner);
            weightedScoreSum += score * W_SSH;
            weightSum += W_SSH;
        }

        // 8. HTTP Server Header
        if (hasValue(candidate.httpServerHeader) || hasValue(historical.httpServerHeader)) {
            double score = compareStringsContains(candidate.httpServerHeader, historical.httpServerHeader);
            weightedScoreSum += score * W_HTTP;
            weightSum += W_HTTP;
        }

        // 9. TLS JA4 Signature
        if (hasValue(candidate.tlsJa4) || hasValue(historical.tlsJa4)) {
            double score = compareStrings(candidate.tlsJa4, historical.tlsJa4);
            weightedScoreSum += score * W_TLS_JA4;
            weightSum += W_TLS_JA4;
        }

        // 10. TLS Certificate Subject
        if (hasValue(candidate.tlsCertSubject) || hasValue(historical.tlsCertSubject)) {
            double score = compareStringsContains(candidate.tlsCertSubject, historical.tlsCertSubject);
            weightedScoreSum += score * W_TLS_CERT;
            weightSum += W_TLS_CERT;
        }

        // Return normalized similarity. If no common signals were present, return 0.0.
        return weightSum > 0.0 ? (weightedScoreSum / weightSum) : 0.0;
    }

    private boolean hasValue(String val) {
        return val != null && !val.trim().isEmpty();
    }

    private <T> boolean hasList(List<T> list) {
        return list != null && !list.isEmpty();
    }

    private double compareStrings(String a, String b) {
        if (a == null || b == null) return 0.0;
        return a.trim().equalsIgnoreCase(b.trim()) ? 1.0 : 0.0;
    }

    private double compareStringsContains(String a, String b) {
        if (a == null || b == null) return 0.0;
        String cleanA = a.trim().toLowerCase();
        String cleanB = b.trim().toLowerCase();
        if (cleanA.equals(cleanB)) return 1.0;
        // Partial overlap score (e.g. if one contains the other)
        if (cleanA.contains(cleanB) || cleanB.contains(cleanA)) return 0.8;
        return 0.0;
    }

    private <T> double compareLists(List<T> a, List<T> b) {
        if (a == null || b == null) return 0.0;
        Set<T> setA = new HashSet<>(a);
        Set<T> setB = new HashSet<>(b);
        
        Set<T> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        Set<T> union = new HashSet<>(setA);
        union.addAll(setB);

        if (union.isEmpty()) return 0.0;
        return (double) intersection.size() / (double) union.size();
    }
}
