package com.gnm.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class SubnetFilterTest {

    @Inject
    SubnetFilter subnetFilter;

    @Test
    void testIsIpInSubnet() {
        assertTrue(subnetFilter.isIpInSubnet("192.168.1.100"));
        assertFalse(subnetFilter.isIpInSubnet(null));
        assertFalse(subnetFilter.isIpInSubnet(""));
        assertFalse(subnetFilter.isIpInSubnet("0.0.0.0"));
        assertFalse(subnetFilter.isIpInSubnet("255.255.255.255"));
    }

    @Test
    void testMatchesCidr() {
        assertTrue(SubnetFilter.matchesCidr("192.168.1.50", "192.168.1.0/24"));
        assertFalse(SubnetFilter.matchesCidr("10.0.0.1", "192.168.1.0/24"));
        assertTrue(SubnetFilter.matchesCidr("10.0.0.1", "10.0.0.1"));
        assertFalse(SubnetFilter.matchesCidr("invalid_ip", "192.168.1.0/24"));
    }
}
