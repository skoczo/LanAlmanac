package com.gnm.fingerprint;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.gnm.model.*;
import com.gnm.model.enums.*;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class FingerprintEngineTest {

    @Inject
    FingerprintEngine engine;

    @BeforeEach
    @Transactional
    public void cleanDatabase() {
        // Clean out previous records to avoid test collisions
        NetworkSighting.deleteAll();
        NetworkIdentity.deleteAll();
        FingerprintVector.deleteAll();
        PhysicalDevice.deleteAll();
    }

    @Test
    public void testSightingCreatesNewDevice() {
        NetworkSighting sighting = new NetworkSighting();
        sighting.ipAddress = "192.168.1.105";
        sighting.macAddress = "00:11:22:33:44:55";
        sighting.source = "TEST_SOURCE";
        sighting.observedAt = Instant.now();
        sighting.rawMetadata = "{\"dhcpOption55\":\"1,3,6,15\",\"services\":[\"_airplay._tcp\"]}";

        engine.processSighting(sighting);

        // Assert device exists
        assertEquals(1, PhysicalDevice.count());
        PhysicalDevice dev = PhysicalDevice.findAll().firstResult();
        assertNotNull(dev);
        assertEquals("Discovered Host 192.168.1.105", dev.displayName);
        assertEquals(DeviceStatus.ONLINE, dev.status);

        // Assert identity is linked
        assertEquals(1, NetworkIdentity.count());
        NetworkIdentity id = NetworkIdentity.findAll().firstResult();
        assertNotNull(id);
        assertEquals("192.168.1.105", id.ipAddress);
        assertEquals("00:11:22:33:44:55", id.macAddress);
        assertEquals(dev.id, id.physicalDevice.id);

        // Assert fingerprint vector saved
        assertEquals(1, FingerprintVector.count());
        FingerprintVector f = FingerprintVector.findAll().firstResult();
        assertNotNull(f);
        assertEquals("1,3,6,15", f.dhcpOption55);
        assertTrue(f.mdnsServices.contains("_airplay._tcp"));
    }

    @Test
    @Transactional
    public void testRandomizedMacMergesToExistingDevice() {
        Instant past = Instant.now().minusSeconds(3600);

        // 1. Manually create an existing device profile in the database
        PhysicalDevice existingDevice = new PhysicalDevice();
        existingDevice.displayName = "Anna's iPhone";
        existingDevice.deviceType = DeviceType.PHONE;
        existingDevice.firstSeen = past;
        existingDevice.lastSeen = past;
        existingDevice.status = DeviceStatus.ONLINE;
        existingDevice.confidenceScore = 1.0;
        existingDevice.persist();

        FingerprintVector existingFingerprint = new FingerprintVector();
        existingFingerprint.physicalDevice = existingDevice;
        existingFingerprint.dhcpOption55 = "1,3,6,15,119,252";
        existingFingerprint.mdnsServices = List.of("_airplay._tcp", "_raop._tcp");
        existingFingerprint.capturedAt = past;
        existingFingerprint.persist();

        NetworkIdentity existingIdentity = new NetworkIdentity();
        existingIdentity.physicalDevice = existingDevice;
        existingIdentity.ipAddress = "192.168.1.50";
        existingIdentity.macAddress = "AA:BB:CC:11:22:33"; // Old stable MAC
        existingIdentity.firstSeen = past;
        existingIdentity.lastSeen = past;
        existingIdentity.current = true;
        existingIdentity.persist();

        // 2. Simulate sighting of the same device with a randomized MAC and new IP
        NetworkSighting randomizedSighting = new NetworkSighting();
        randomizedSighting.ipAddress = "192.168.1.75"; // New IP lease
        randomizedSighting.macAddress = "44:55:66:77:88:99"; // Randomized MAC address
        randomizedSighting.source = "DHCP_SNIFF";
        randomizedSighting.observedAt = Instant.now();
        randomizedSighting.rawMetadata = "{\"dhcpOption55\":\"1,3,6,15,119,252\",\"services\":[\"_airplay._tcp\",\"_raop._tcp\"]}";

        // 3. Process sighting
        engine.processSighting(randomizedSighting);

        // 4. Assertions
        // No new device should have been created since it matched the existing one
        assertEquals(1, PhysicalDevice.count(), "Should merge into the existing device instead of creating a new one");
        
        // A new identity mapping should be created (total of 2 identities for the same device)
        assertEquals(2, NetworkIdentity.count());

        PhysicalDevice matchedDevice = PhysicalDevice.findById(existingDevice.id);
        assertNotNull(matchedDevice);
        
        // Check if both identities point to the same physical device ID
        List<NetworkIdentity> identities = NetworkIdentity.list("physicalDevice.id", matchedDevice.id);
        assertEquals(2, identities.size());
        
        boolean foundNewIdentity = identities.stream()
                .anyMatch(id -> id.ipAddress.equals("192.168.1.75") && id.macAddress.equals("44:55:66:77:88:99"));
        assertTrue(foundNewIdentity, "A new identity record should be linked to the existing device");

        // Assert current flags are updated correctly
        NetworkIdentity oldId = NetworkIdentity.find("ipAddress = ?1", "192.168.1.50").firstResult();
        NetworkIdentity newId = NetworkIdentity.find("ipAddress = ?1", "192.168.1.75").firstResult();
        
        assertNotNull(oldId);
        assertNotNull(newId);
        assertFalse(oldId.current, "Old identity should be marked as non-current");
        assertTrue(newId.current, "New identity should be marked as current");
    }

    @Test
    @Transactional
    public void testSightingOnOldIdentityDeactivatesNewerIdentity() {
        Instant past = Instant.now().minusSeconds(3600);

        PhysicalDevice device = new PhysicalDevice();
        device.displayName = "Test Device";
        device.deviceType = DeviceType.PHONE;
        device.firstSeen = past;
        device.lastSeen = past;
        device.status = DeviceStatus.ONLINE;
        device.confidenceScore = 1.0;
        device.persist();

        FingerprintVector fingerprint = new FingerprintVector();
        fingerprint.physicalDevice = device;
        fingerprint.dhcpOption55 = "1,3,6,15,119,252";
        fingerprint.capturedAt = past;
        fingerprint.persist();

        // Identity A (old IP) starts as non-current
        NetworkIdentity idA = new NetworkIdentity();
        idA.physicalDevice = device;
        idA.ipAddress = "192.168.1.50";
        idA.macAddress = "AA:BB:CC:11:22:33";
        idA.firstSeen = past;
        idA.lastSeen = past;
        idA.current = false;
        idA.persist();

        // Identity B (newer IP) starts as current
        NetworkIdentity idB = new NetworkIdentity();
        idB.physicalDevice = device;
        idB.ipAddress = "192.168.1.75";
        idB.macAddress = "44:55:66:77:88:99";
        idB.firstSeen = past;
        idB.lastSeen = past;
        idB.current = true;
        idB.persist();

        // Simulate sighting on the old IP (192.168.1.50)
        NetworkSighting oldIpSighting = new NetworkSighting();
        oldIpSighting.ipAddress = "192.168.1.50";
        oldIpSighting.macAddress = "AA:BB:CC:11:22:33";
        oldIpSighting.source = "ARP_CACHE_FALLBACK";
        oldIpSighting.observedAt = Instant.now();
        oldIpSighting.rawMetadata = "{}";

        engine.processSighting(oldIpSighting);

        // Fetch refreshed entities
        idA = NetworkIdentity.findById(idA.id);
        idB = NetworkIdentity.findById(idB.id);

        assertTrue(idA.current, "Sighted identity should become current");
        assertFalse(idB.current, "Other identity should be deactivated");
    }
    @Test
    @Transactional
    public void testPlaceholderMacUnifiesToExistingIdentity() {
        Instant past = Instant.now().minusSeconds(3600);

        // 1. Manually create an existing device profile with a placeholder MAC
        PhysicalDevice existingDevice = new PhysicalDevice();
        existingDevice.displayName = "Placeholder Device";
        existingDevice.deviceType = DeviceType.IOT;
        existingDevice.firstSeen = past;
        existingDevice.lastSeen = past;
        existingDevice.status = DeviceStatus.ONLINE;
        existingDevice.confidenceScore = 1.0;
        existingDevice.persist();

        NetworkIdentity existingIdentity = new NetworkIdentity();
        existingIdentity.physicalDevice = existingDevice;
        existingIdentity.ipAddress = "192.168.1.61";
        existingIdentity.macAddress = "00:00:00:00:00:00"; // Placeholder MAC
        existingIdentity.firstSeen = past;
        existingIdentity.lastSeen = past;
        existingIdentity.current = true;
        existingIdentity.persist();

        // 2. Simulate sighting of the same device with the REAL MAC
        NetworkSighting realMacSighting = new NetworkSighting();
        realMacSighting.ipAddress = "192.168.1.61";
        realMacSighting.macAddress = "E0:51:D8:15:55:AC";
        realMacSighting.source = "TEST_SOURCE";
        realMacSighting.observedAt = Instant.now();

        engine.processSighting(realMacSighting);

        // Assert no new device was created
        assertEquals(1, PhysicalDevice.count());
        PhysicalDevice dev = PhysicalDevice.findAll().firstResult();
        assertEquals("Placeholder Device", dev.displayName);

        // Assert identity was upgraded in place
        assertEquals(1, NetworkIdentity.count());
        NetworkIdentity upgradedId = NetworkIdentity.findAll().firstResult();
        assertEquals("192.168.1.61", upgradedId.ipAddress);
        assertEquals("E0:51:D8:15:55:AC", upgradedId.macAddress); // Unified!
    }
}
