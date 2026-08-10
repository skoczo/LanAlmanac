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

        // Set DEVICE_OFFLINE_TIMEOUT_MINUTES to 0 so inactivity sweep triggers immediately
        String settingPayload = "{\"value\": \"0\"}";
        given()
            .contentType("application/json")
            .body(settingPayload)
            .when().put("/api/settings/DEVICE_OFFLINE_TIMEOUT_MINUTES")
            .then().statusCode(200);

        // When: We stop the ne-linux-server container
        ProcessBuilder pbStop = new ProcessBuilder("docker", "compose", "-f", "../docker-compose.e2e.yml", "stop",
                "ne-linux-server");
        pbStop.start().waitFor();

        // Then: Wait for the application's inactivity check to mark this specific
        // device as offline. The background job runs every 1 minute, so we wait up to 70 seconds.
        boolean isOffline = false;
        for (int i = 0; i < 350; i++) { // Up to 70 seconds
            io.restassured.response.Response res = given().when().get("/api/devices");
            if (res.statusCode() == 200) {
                // We should also assert that there are devices in the list (num of devices)
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
        assertTrue(isOffline, "Linux server (192.168.100.10) should be marked as OFFLINE");
    }
}
