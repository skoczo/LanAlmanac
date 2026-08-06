package com.gnm.resource;

import com.gnm.model.NetworkIdentity;
import com.gnm.model.PhysicalDevice;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
public class WebProxyResourceTest {

    @BeforeEach
    @Transactional
    public void setup() {
        PhysicalDevice.deleteAll();

        PhysicalDevice device = new PhysicalDevice();
        device.displayName = "Test Device";
        device.firstSeen = Instant.now();
        device.lastSeen = Instant.now();
        device.persist();

        NetworkIdentity identity = new NetworkIdentity();
        identity.physicalDevice = device;
        identity.ipAddress = "127.0.0.1"; // pointing to localhost for test
        identity.macAddress = "00:11:22:33:44:55";
        identity.current = true;
        identity.firstSeen = Instant.now();
        identity.lastSeen = Instant.now();
        identity.persist();
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testProxyGet() {
        PhysicalDevice device = PhysicalDevice.findAll().firstResult();

        given()
          .when().get("/api/proxy/" + device.id + "/api/health")
          .then()
             .statusCode(500)
             .body(containsString("Proxy error"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testProxyNotFoundDevice() {
        given()
          .when().get("/api/proxy/00000000-0000-0000-0000-000000000000/")
          .then()
             .statusCode(404);
    }
}
