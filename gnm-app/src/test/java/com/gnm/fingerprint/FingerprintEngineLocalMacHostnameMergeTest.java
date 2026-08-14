package com.gnm.fingerprint;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;

import com.gnm.model.*;
import com.gnm.model.enums.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that a device with a locally-administered (randomized) MAC address
 * is correctly merged into an existing PhysicalDevice when it reappears
 * with the same hostname but a different MAC and IP address.
 *
 * This tests the fix for the bug where the simulated Android device (which randomizes its MAC
 * every 5 minutes via DHCP) was creating a new PhysicalDevice on every MAC change,
 * resulting in dozens of phantom devices instead of one.
 *
 * Root cause: FingerprintVector.hostname was @Transient (not persisted), so the
 * similarity engine could never use the hostname signal across DB queries. Combined
 * with an empty fingerprint (no DHCP options, mDNS, or open ports for minimal devices),
 * the similarity score was always 0.0, below the merge threshold.
 *
 * Fix: hostname is now persisted in fingerprint_vector, and FingerprintEngine has a
 * hostname-based pre-merge path for locally-administered MACs.
 */
@QuarkusTest
public class FingerprintEngineLocalMacHostnameMergeTest {

    @Inject
    FingerprintEngine engine;

    @BeforeEach
    @Transactional
    public void cleanDatabase() {
        NetworkSighting.deleteAll();
        NetworkIdentity.deleteAll();
        FingerprintVector.deleteAll();
        PhysicalDevice.deleteAll();
    }

    /**
     * Scenario: Android phone with locally-administered MAC (02:xx:xx:xx:xx:xx) appears
     * initially at IP .50, then MAC-randomizes and reappears at IP .73 with a new MAC.
     * Both sightings have hostname "android-phone.lan" AND the same DHCP Option 55 fingerprint.
     * The second sighting MUST merge into the existing device, not create a new one.
     *
     * NOTE: The SimilarityEngine now requires at least 2 independent matching signals
     * to produce a score above the merge threshold. Hostname alone is capped at 0.5
     * (below the 0.75 merge threshold) to prevent hostname-spoofing attacks.
     */
    @Test
    public void testLocalMacWithSameHostnameMergesToExistingDevice() throws InterruptedException {
        // Step 1: First sighting - creates the initial device with DHCP fingerprint
        NetworkSighting firstSighting = new NetworkSighting();
        firstSighting.ipAddress = "172.20.0.50";
        firstSighting.macAddress = "02:AB:CD:EF:12:34"; // Locally-administered (02:xx prefix)
        firstSighting.source = "DHCP_SNIFF";
        firstSighting.observedAt = Instant.now();
        firstSighting.rawMetadata = "{\"dhcpOption55\":\"1,3,6,15,28,42\",\"host\":\"android-phone.lan\"}";

        engine.processSighting(firstSighting);

        // Verify initial state: 1 device created
        assertEquals(1, PhysicalDevice.count(), "First sighting should create exactly 1 device");
        assertEquals(1, NetworkIdentity.count());
        assertEquals(1, FingerprintVector.count());

        // Verify the fingerprint has both hostname and DHCP option persisted
        FingerprintVector fv = FingerprintVector.findAll().firstResult();
        assertNotNull(fv, "FingerprintVector should exist");
        assertEquals("android-phone.lan", fv.hostname,
                "Hostname should be persisted in FingerprintVector");
        assertEquals("1,3,6,15,28,42", fv.dhcpOption55,
                "DHCP Option 55 should be persisted in FingerprintVector");

        // Step 2: Same device with new (randomized) MAC and new DHCP IP, same fingerprint
        NetworkSighting secondSighting = new NetworkSighting();
        secondSighting.ipAddress = "172.20.0.73"; // New DHCP-assigned IP
        secondSighting.macAddress = "02:FE:DC:BA:98:76"; // New randomized locally-administered MAC
        secondSighting.source = "DHCP_SNIFF";
        secondSighting.observedAt = Instant.now();
        secondSighting.rawMetadata = "{\"dhcpOption55\":\"1,3,6,15,28,42\",\"host\":\"android-phone.lan\"}";

        engine.processSighting(secondSighting);

        // Step 3: Assert that the second sighting merged into the existing device
        long deviceCount = PhysicalDevice.count();
        assertEquals(1, deviceCount,
                "Second sighting from same device (different randomized MAC + same hostname + same DHCP) " +
                "should MERGE into the existing PhysicalDevice, NOT create a new one. " +
                "Found " + deviceCount + " devices instead of 1.");

        // Should have 2 identities now (one per MAC/IP pair)
        assertEquals(2, NetworkIdentity.count(),
                "Should have 2 network identity records (one per IP/MAC lease)");

        // Both identities should point to the SAME physical device
        List<NetworkIdentity> identities = NetworkIdentity.listAll();
        assertEquals(identities.get(0).physicalDevice.id, identities.get(1).physicalDevice.id,
                "Both identities must reference the same PhysicalDevice");

        // The new identity should be current; the old one should not
        NetworkIdentity oldIdentity = identities.stream()
                .filter(id -> id.ipAddress.equals("172.20.0.50"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Old identity at 172.20.0.50 not found"));
        NetworkIdentity newIdentity = identities.stream()
                .filter(id -> id.ipAddress.equals("172.20.0.73"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("New identity at 172.20.0.73 not found"));

        assertFalse(oldIdentity.current, "Old identity (first MAC) should be deactivated");
        assertTrue(newIdentity.current, "New identity (second randomized MAC) should be current");
        assertEquals("02:FE:DC:BA:98:76", newIdentity.macAddress, "New identity should have the second randomized MAC");
    }

    /**
     * Scenario: Two DIFFERENT devices with locally-administered MACs but DIFFERENT hostnames
     * must NOT be merged. Each should remain as its own PhysicalDevice.
     */
    @Test
    public void testLocalMacWithDifferentHostnamesDoesNotMerge() throws InterruptedException {
        // Device 1
        NetworkSighting firstDevice = new NetworkSighting();
        firstDevice.ipAddress = "172.20.0.50";
        firstDevice.macAddress = "02:AA:BB:CC:DD:EE";
        firstDevice.source = "ARP_CACHE_FALLBACK";
        firstDevice.observedAt = Instant.now();
        firstDevice.rawMetadata = "{\"host\":\"android-phone.lan\"}";
        engine.processSighting(firstDevice);
        setDeviceHostnameAndFingerprintHostname("172.20.0.50", "android-phone.lan");

        // Device 2 - different hostname, different locally-administered MAC
        NetworkSighting secondDevice = new NetworkSighting();
        secondDevice.ipAddress = "172.20.0.60";
        secondDevice.macAddress = "02:11:22:33:44:55";
        secondDevice.source = "ARP_CACHE_FALLBACK";
        secondDevice.observedAt = Instant.now();
        secondDevice.rawMetadata = "{\"host\":\"iphone-pro.lan\"}";
        engine.processSighting(secondDevice);

        // Both should remain as separate devices
        assertEquals(2, PhysicalDevice.count(),
                "Two devices with different hostnames should NOT be merged, even with locally-administered MACs");
    }

    /**
     * Scenario: A device with a globally-unique (permanent hardware) MAC is never
     * affected by the hostname-based merge path. Two devices with the same hostname
     * but different globally-unique MACs must remain separate.
     */
    @Test
    public void testGloballyUniqueMacIsNotMergedByHostname() throws InterruptedException {
        // Two sightings with globally-unique MACs but same hostname
        // (unlikely in practice but must not merge)
        NetworkSighting first = new NetworkSighting();
        first.ipAddress = "172.20.0.10";
        first.macAddress = "00:50:56:00:01:AA"; // Globally unique (VMware OUI, bit 1 of first octet = 0)
        first.source = "ARP_CACHE_FALLBACK";
        first.observedAt = Instant.now();
        first.rawMetadata = "{\"host\":\"ubuntu-ne\"}";
        engine.processSighting(first);
        setDeviceHostnameAndFingerprintHostname("172.20.0.10", "ubuntu-ne");

        NetworkSighting second = new NetworkSighting();
        second.ipAddress = "172.20.0.11";
        second.macAddress = "00:50:56:00:02:BB"; // Different globally-unique MAC
        second.source = "ARP_CACHE_FALLBACK";
        second.observedAt = Instant.now();
        second.rawMetadata = "{\"host\":\"ubuntu-ne\"}"; // Same hostname
        engine.processSighting(second);

        // Globally unique MACs must never be merged via hostname alone
        assertEquals(2, PhysicalDevice.count(),
                "Devices with different globally-unique MACs must not be merged even if they share a hostname");
    }

    /**
     * Helper: updates the displayName of the device at the given IP and sets the
     * persisted hostname in its FingerprintVector. This simulates the async hostname
     * resolution that happens after the sighting is processed in production.
     */
    @Transactional
    void setDeviceHostnameAndFingerprintHostname(String ipAddress, String hostname) {
        NetworkIdentity identity = NetworkIdentity.find("ipAddress = ?1 and current = true", ipAddress).firstResult();
        if (identity == null) {
            // Try without current filter
            identity = NetworkIdentity.find("ipAddress = ?1", ipAddress).firstResult();
        }
        if (identity != null && identity.physicalDevice != null) {
            identity.hostname = hostname;
            identity.persist();
            PhysicalDevice device = identity.physicalDevice;
            if (device.displayName.startsWith("Discovered Host ")) {
                device.displayName = hostname;
                device.persist();
            }
            FingerprintVector fv = FingerprintVector.find("physicalDevice.id = ?1", device.id).firstResult();
            if (fv != null) {
                fv.hostname = hostname;
                fv.persist();
            }
        }
    }
}
