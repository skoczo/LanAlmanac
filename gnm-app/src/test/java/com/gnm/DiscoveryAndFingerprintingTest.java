package com.gnm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class DiscoveryAndFingerprintingTest extends AbstractE2ETest {

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testActiveScanDiscoversDevices() throws Exception {
        // Given: The ne-router-sim container is running (192.168.100.20)
        // When: A manual discovery is triggered via the API
        String scanPayload = "{\"ipAddress\": \"192.168.100.20\"}";

        given()
                .contentType("application/json")
                .body(scanPayload)
                .when().post("/api/devices/discover")
                .then()
                .statusCode(202);

        // Then: Wait for scan to complete and verify the device is added
        boolean discovered = false;
        for (int i = 0; i < 50; i++) {
            io.restassured.response.Response res = given().when().get("/api/devices");
            if (res.statusCode() == 200) {
                boolean match = res.jsonPath().getList(".").stream().anyMatch(device -> {
                    java.util.Map<String, Object> d = (java.util.Map<String, Object>) device;
                    java.util.List<java.util.Map<String, Object>> identities = (java.util.List<java.util.Map<String, Object>>) d
                            .get("identities");

                    if (identities != null) {
                        return identities.stream().anyMatch(ident -> "192.168.100.20".equals(ident.get("ipAddress")));
                    }
                    return false;
                });

                if (match) {
                    discovered = true;
                    break;
                }
            }
            Thread.sleep(200);
        }
        assertTrue(discovered, "Active scan should discover the router simulator at 192.168.100.20");
    }

    @Inject
    com.gnm.discovery.NetworkSightingQueue sightingQueue;

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testPassiveDhcpSniffing() throws Exception {
        // Given: In TEST mode, the passive packet sniffer is disabled because the test JVM
        // doesn't have Layer 2 access to the Docker bridge network. 
        // We simulate the PassivePacketListener intercepting a DHCP broadcast.
        
        // When: We manually enqueue a simulated DHCP sighting for the traffic generator IP
        com.gnm.model.NetworkSighting sighting = new com.gnm.model.NetworkSighting();
        sighting.ipAddress = "192.168.100.30";
        sighting.macAddress = "00:11:22:33:44:55";
        sighting.source = "DHCP_SNIFF";
        sighting.observedAt = java.time.Instant.now();
        sighting.rawMetadata = "{\"dhcpOption55\":\"1,3,6,15,119,252\",\"dhcpOption60\":\"client-device\"}";
        
        sightingQueue.offer(sighting);
        
        // Then: The backend FingerprintEngine should process the sighting and discover the device
        boolean discovered = false;
        for (int i = 0; i < 50; i++) {
            io.restassured.response.Response res = given().when().get("/api/devices");
            if (res.statusCode() == 200) {
                boolean match = res.jsonPath().getList(".").stream().anyMatch(device -> {
                    java.util.Map<String, Object> d = (java.util.Map<String, Object>) device;
                    java.util.List<java.util.Map<String, Object>> identities = 
                        (java.util.List<java.util.Map<String, Object>>) d.get("identities");
                    
                    if (identities != null) {
                        return identities.stream().anyMatch(ident -> "192.168.100.30".equals(ident.get("ipAddress")));
                    }
                    return false;
                });
                
                if (match) {
                    discovered = true;
                    break;
                }
            }
            Thread.sleep(200);
        }
        assertTrue(discovered, "Passive sniffer should detect DHCP broadcast from traffic generator at 192.168.100.30");
    }
}
