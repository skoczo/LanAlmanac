package com.gnm.fingerprint;

import com.gnm.model.*;
import com.gnm.model.enums.DeviceStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class DeviceIdentityManagerTest {

    @Inject
    DeviceIdentityManager identityManager;

    @BeforeEach
    @Transactional
    public void setup() {
        NetworkSighting.deleteAll();
        FingerprintCorrelationEvent.deleteAll();
        NetworkService.deleteAll();
        NetworkIdentity.deleteAll();
        DeviceStatusHistory.deleteAll();
        FingerprintVector.deleteAll();
        PhysicalDevice.deleteAll();
        ThreatEvent.deleteAll();
        GlobalSetting.deleteAll();
    }

    @Test
    @Transactional
    public void testSaveSightingCreatesNewDevice() {
        NetworkSighting sighting = new NetworkSighting();
        sighting.ipAddress = "192.168.10.100";
        sighting.macAddress = "00:11:22:33:44:55";
        sighting.source = "MANUAL_DISCOVERY";
        sighting.observedAt = Instant.now();

        FingerprintVector candidate = new FingerprintVector();
        candidate.openPorts = List.of(22, 80);
        candidate.hostname = "webserver.local";

        identityManager.saveSightingInTransaction(sighting, candidate, "webserver.local");

        PhysicalDevice dev = PhysicalDevice.find("displayName", "webserver.local").firstResult();
        assertNotNull(dev);
        assertEquals(DeviceStatus.ONLINE, dev.status);

        // Verify services auto-created for ports 22 and 80
        List<NetworkService> services = NetworkService.list("physicalDevice.id", dev.id);
        assertFalse(services.isEmpty());
        assertTrue(services.stream().anyMatch(s -> s.port == 22 && "SSH".equals(s.serviceType)));
        assertTrue(services.stream().anyMatch(s -> s.port == 80 && "HTTP".equals(s.serviceType)));
    }

    @Test
    @Transactional
    public void testIpUniquenessEnforcement() {
        // Device 1 on 192.168.10.50
        NetworkSighting sighting1 = new NetworkSighting();
        sighting1.ipAddress = "192.168.10.50";
        sighting1.macAddress = "AA:BB:CC:11:22:33";
        sighting1.source = "MANUAL_DISCOVERY";
        sighting1.observedAt = Instant.now();
        identityManager.saveSightingInTransaction(sighting1, new FingerprintVector(), "host1");

        // Device 2 claims 192.168.10.50 (IP takeover)
        NetworkSighting sighting2 = new NetworkSighting();
        sighting2.ipAddress = "192.168.10.50";
        sighting2.macAddress = "DD:EE:FF:44:55:66";
        sighting2.source = "MANUAL_DISCOVERY";
        sighting2.observedAt = Instant.now();
        identityManager.saveSightingInTransaction(sighting2, new FingerprintVector(), "host2");

        // Verify only Device 2's identity has current = true for 192.168.10.50
        List<NetworkIdentity> activeOnIp = NetworkIdentity.find("ipAddress = '192.168.10.50' and current = true").list();
        assertEquals(1, activeOnIp.size());
        assertEquals("DD:EE:FF:44:55:66", activeOnIp.get(0).macAddress);
    }

    @Test
    public void testDetectionModeGeneratesThreat() {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            GlobalSetting mode = new GlobalSetting();
            mode.key = "APP_MODE";
            mode.value = "DETECTION";
            mode.persist();
        });

        NetworkSighting sighting = new NetworkSighting();
        sighting.ipAddress = "192.168.10.99";
        sighting.macAddress = "11:22:33:44:55:66";
        sighting.source = "DHCP_SNIFF";
        sighting.observedAt = Instant.now();

        identityManager.saveSightingInTransaction(sighting, new FingerprintVector(), "rogue-host");

        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            // Device should NOT be created in detection mode
            assertEquals(0, PhysicalDevice.count());

            // ThreatEvent should be created
            assertEquals(1, ThreatEvent.count());
            ThreatEvent threat = ThreatEvent.findAll().firstResult();
            assertEquals("192.168.10.99", threat.ipAddress);
            assertEquals("CRITICAL", threat.severity);
        });
    }
}
