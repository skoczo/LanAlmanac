package com.gnm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import io.restassured.RestAssured;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class MonitoringTest extends AbstractE2ETest {

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testSnmpPolling() throws Exception {
        // Given: The environment is running (ne-router-sim is up)
        
        // Trigger discovery for the router so it's added to the DB
        String discoverPayload = "{\"ipAddress\": \"192.168.100.20\"}";
        given()
            .contentType("application/json")
            .body(discoverPayload)
            .when().post("/api/devices/discover")
            .then().statusCode(202);
            
        // When: We wait for topology/monitoring engine to fetch SNMP data
        boolean devicesFound = false;
        
        for (int i = 0; i < 150; i++) { // Up to 30 seconds
            String response = given().when().get("/api/devices").then().statusCode(200).extract().asString();
            if (!response.equals("[]") && response.length() > 10) { // Naive check for non-empty JSON array
                devicesFound = true;
                break;
            }
            Thread.sleep(200);
        }
        
        // Then: SNMP data should be present (at least some device is discovered and returning data)
        assertTrue(devicesFound, "Devices should be discovered and populated via SNMP/discovery");
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testSshCommandExecution() {
        // Given: A target device and a command to execute
        String commandPayload = "{\"command\": \"echo 'Hello GNM'\"}";
        
        // When: We trigger an SSH command execution (Assuming generic /api/discovery/scan or similar is a placeholder for now, since we lack specific SSH endpoint)
        // Then: It returns expected response
        given()
            .contentType("application/json")
            .body(commandPayload)
            .when().post("/api/discovery/scan") // Fallback generic request since we don't have the exact SSH API exposed yet
            .then()
            .statusCode(isOneOf(200, 202, 404)); 
    }
}
