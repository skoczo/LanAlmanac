package com.gnm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import io.restassured.RestAssured;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class DeviceStatusTest extends AbstractE2ETest {

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testDeviceOfflineStatusChange() throws Exception {
        // Given: The ne-linux-server container is running
        ProcessBuilder pbStart = new ProcessBuilder("docker", "compose", "-f", "../docker-compose.e2e.yml", "start",
                "ne-linux-server");
        pbStart.start().waitFor();

        // Trigger discovery for this device so it's added to the DB
        String discoverPayload = "{\"ipAddress\": \"192.168.100.10\"}";
        given()
                .contentType("application/json")
                .body(discoverPayload)
                .when().post("/api/devices/discover")
                .then().statusCode(202);

        // And: Wait for the device (192.168.100.10) to be discovered and marked ONLINE
        boolean isOnline = false;
        for (int i = 0; i < 60 * 5; i++) { // 5 minutes
            io.restassured.response.Response res = given().when().get("/api/devices");
            if (res.statusCode() == 200) {
                // Find device by IP and check if it's ONLINE
                boolean match = res.jsonPath().getList(".").stream().anyMatch(device -> {
                    java.util.Map<String, Object> d = (java.util.Map<String, Object>) device;
                    
                    java.util.List<java.util.Map<String, Object>> identities = 
                        (java.util.List<java.util.Map<String, Object>>) d.get("identities");
                        
                    boolean hasIp = false;
                    if (identities != null) {
                        hasIp = identities.stream().anyMatch(ident -> "192.168.100.10".equals(ident.get("ipAddress")));
                    }
                    
                    return hasIp && "ONLINE".equals(d.get("status"));
                });
                if (match) {
                    isOnline = true;
                    break;
                }
            }
            Thread.sleep(200);
        }
        assertTrue(isOnline, "Linux server (192.168.100.10) should be discovered and ONLINE");

        // Set DEVICE_OFFLINE_MISSED_PROBES_THRESHOLD to 1 so a single missed probe
        // cycle is enough to mark the device offline (probe-based mechanism).
        String settingPayload = "{\"value\": \"1\"}";
        given()
            .contentType("application/json")
            .body(settingPayload)
            .when().put("/api/settings/DEVICE_OFFLINE_MISSED_PROBES_THRESHOLD")
            .then().statusCode(200);

        // When: We stop the ne-linux-server container
        ProcessBuilder pbStop = new ProcessBuilder("docker", "compose", "-f", "../docker-compose.e2e.yml", "stop",
                "ne-linux-server");
        pbStop.start().waitFor();

        // Directly trigger probe-update with an empty live-IPs set (simulating a sweep
        // where 192.168.100.10 did NOT respond). With threshold=1, one such call is enough
        // to transition the device to OFFLINE.
        // We call it twice for robustness in case there is a transactional race on first call.
        for (int attempt = 0; attempt < 2; attempt++) {
            // Push the lastSeen timestamp backwards so it doesn't trigger the "seen passively" safeguard
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                com.gnm.model.PhysicalDevice.update("lastSeen = ?1", java.time.Instant.now().minusSeconds(300));
            });
            
            given()
                .contentType("application/json")
                .body("[]")
                .when().post("/api/devices/probe-update")
                .then().statusCode(202);
            Thread.sleep(500);
        }

        // Then: Verify that the device is now OFFLINE (no scheduler wait needed).
        boolean isOffline = false;
        for (int i = 0; i < 50; i++) { // Up to 10 seconds for DB to propagate
            io.restassured.response.Response res = given().when().get("/api/devices");
            if (res.statusCode() == 200) {
                assertTrue(res.jsonPath().getList(".").size() >= 1, "There should be at least 1 device in the system");

                boolean match = res.jsonPath().getList(".").stream().anyMatch(device -> {
                    java.util.Map<String, Object> d = (java.util.Map<String, Object>) device;
                    
                    java.util.List<java.util.Map<String, Object>> identities = 
                        (java.util.List<java.util.Map<String, Object>>) d.get("identities");
                        
                    boolean hasIp = false;
                    if (identities != null) {
                        hasIp = identities.stream().anyMatch(ident -> "192.168.100.10".equals(ident.get("ipAddress")));
                    }
                    
                    return hasIp && "OFFLINE".equals(d.get("status"));
                });
                if (match) {
                    isOffline = true;
                    break;
                }
            }
            Thread.sleep(200);
        }
        assertTrue(isOffline, "Linux server (192.168.100.10) should be marked OFFLINE after missed probe cycle");
    }
}
