package com.gnm.resource;

import com.gnm.AbstractE2ETest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;

import com.gnm.model.ThreatEvent;
import com.gnm.model.PhysicalDevice;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ThreatResourceTest extends AbstractE2ETest {

    @Test
    @TestSecurity(user = "admin", roles = {"gnm-admin", "ADMIN"})
    void testApproveDeviceResolvesAllAlertsForSameMac() {
        String testMac = "AA:BB:CC:DD:EE:FF";
        final java.util.concurrent.atomic.AtomicReference<java.util.UUID> threat1Id = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<java.util.UUID> threat2Id = new java.util.concurrent.atomic.AtomicReference<>();

        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            ThreatEvent threat1 = new ThreatEvent();
            threat1.severity = "CRITICAL";
            threat1.description = "Rogue Device Detected: Unauthorized access attempt from TestHost";
            threat1.ipAddress = "192.168.1.200";
            threat1.macAddress = testMac;
            threat1.detectedAt = Instant.now();
            threat1.resolved = false;
            threat1.persist();
            threat1Id.set(threat1.id);

            ThreatEvent threat2 = new ThreatEvent();
            threat2.severity = "CRITICAL";
            threat2.description = "Rogue Device Detected: Unauthorized access attempt from TestHost";
            threat2.ipAddress = "192.168.1.201";
            threat2.macAddress = testMac;
            threat2.detectedAt = Instant.now();
            threat2.resolved = false;
            threat2.persist();
            threat2Id.set(threat2.id);
        });

        given()
            .contentType("application/json")
            .body(Map.of("displayName", "Approved Test Phone"))
            .when()
            .post("/api/threats/" + threat1Id.get() + "/approve-device")
            .then()
            .statusCode(200)
            .body("resolved", is(true))
            .body("physicalDeviceId", notNullValue());

        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            ThreatEvent t1After = ThreatEvent.findById(threat1Id.get());
            ThreatEvent t2After = ThreatEvent.findById(threat2Id.get());

            assertTrue(t1After.resolved);
            assertTrue(t2After.resolved);
            assertEquals(t1After.physicalDeviceId, t2After.physicalDeviceId);

            PhysicalDevice dev = PhysicalDevice.findById(t1After.physicalDeviceId);
            assertNotNull(dev);
            assertEquals("Approved Test Phone", dev.displayName);
        });
    }

    @Test
    @TestSecurity(user = "admin", roles = {"gnm-admin", "ADMIN"})
    void testResolveAllAndDeviceThreats() {
        java.util.UUID devId = java.util.UUID.randomUUID();
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            ThreatEvent t1 = new ThreatEvent();
            t1.severity = "HIGH";
            t1.description = "Test threat on device";
            t1.physicalDeviceId = devId;
            t1.detectedAt = Instant.now();
            t1.resolved = false;
            t1.persist();

            ThreatEvent t2 = new ThreatEvent();
            t2.severity = "MEDIUM";
            t2.description = "Global unassigned threat";
            t2.detectedAt = Instant.now();
            t2.resolved = false;
            t2.persist();
        });

        // Resolve by device
        given()
            .when()
            .put("/api/threats/devices/" + devId + "/resolve")
            .then()
            .statusCode(200)
            .body("resolvedCount", greaterThanOrEqualTo(1));

        // Resolve all
        given()
            .when()
            .put("/api/threats/resolve-all")
            .then()
            .statusCode(200)
            .body("resolvedCount", greaterThanOrEqualTo(1));
    }
}
