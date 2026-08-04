package com.gnm.fingerprint;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.gnm.model.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
public class ManualNetworkElementDiscoveryTest {

    private static final Logger LOG = LoggerFactory.getLogger(ManualNetworkElementDiscoveryTest.class);

    @Inject
    FingerprintEngine engine;

    @BeforeEach
    public void setup() {
        System.setProperty("forceNetworkScan", "true");
    }

    @AfterEach
    public void teardown() {
        System.clearProperty("forceNetworkScan");
    }

    @Test
    public void testManualDiscoveryOfSelectedNodes() {
        // Map of IP addresses to MAC addresses from dhcp_backup.txt
        Map<String, String> networkElements = Map.of(
                "192.168.1.37", "8C:CE:4E:18:F4:61",   // roleta-salon
                "192.168.1.176", "F0:00:01:0E:2E:F2",  // kamera-podjazd
                "192.168.1.100", "CE:EB:EE:20:B7:9B",  // HEIMDALL
                "192.168.1.179", "08:A6:F7:A2:00:57",  // SLZB-06
                "192.168.1.32", "BC:FF:4D:38:24:C3",   // falownik / espressif
                "192.168.1.27", "5C:CF:7F:58:19:89",   // tv-switch / telewizor
                "192.168.1.63", "1E:E3:E4:C7:0B:D0",   // homeassistant / skoczo
                "192.168.1.39", "7C:D3:0A:79:89:28",   // BL1-NODE
                "192.168.1.177", "26:28:EA:AA:2B:4C"   // portainer
        );

        LOG.info("=========================================================");
        LOG.info("STARTING MANUAL DISCOVERY TEST FOR {} NETWORK ELEMENTS", networkElements.size());
        LOG.info("=========================================================");

        for (Map.Entry<String, String> entry : networkElements.entrySet()) {
            String ip = entry.getKey();
            String mac = entry.getValue();

            LOG.info("--> Initiating discovery for Target: IP={}, MAC={}", ip, mac);

            NetworkSighting sighting = new NetworkSighting();
            sighting.ipAddress = ip;
            sighting.macAddress = mac;
            sighting.source = "MANUAL_TEST";
            sighting.observedAt = Instant.now();
            sighting.rawMetadata = "{}";

            try {
                // This will trigger the actual live network scan (Ports, JNDI, NetBIOS, TLS)
                engine.processSighting(sighting);

                // Fetch the resulting physical device
                NetworkIdentity id = NetworkIdentity.find("ipAddress", ip).firstResult();
                assertNotNull(id, "Identity should be created for " + ip);
                
                PhysicalDevice device = id.physicalDevice;
                assertNotNull(device, "Device should be created for " + ip);

                FingerprintVector vector = FingerprintVector.find("physicalDevice.id", device.id).firstResult();
                
                LOG.info("<<< DISCOVERY RESULTS FOR {} >>>", ip);
                LOG.info("    Hostname    : {}", device.displayName);
                LOG.info("    Device Type : {}", device.deviceType);
                LOG.info("    Open Ports  : {}", (vector != null && vector.openPorts != null) ? vector.openPorts : "None");
                LOG.info("    Confidence  : {}%", Math.round(device.confidenceScore * 100));
                LOG.info("---------------------------------------------------------");
            } catch (Exception e) {
                LOG.error("Failed to process discovery for " + ip, e);
            }
        }

        LOG.info("=========================================================");
        LOG.info("MANUAL DISCOVERY TEST COMPLETED");
        LOG.info("=========================================================");
    }
}
