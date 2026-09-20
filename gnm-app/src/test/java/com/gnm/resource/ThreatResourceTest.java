package com.gnm.resource;

import com.gnm.AbstractE2ETest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Map;

import com.gnm.model.ThreatEvent;
import com.gnm.model.PhysicalDevice;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class ThreatResourceTest extends AbstractE2ETest {

    @Test
    @TestSecurity(user = "admin", roles = {"gnm-admin", "ADMIN"})
    public void testApproveDeviceResolvesAllAlertsForSameMac() {
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
}
