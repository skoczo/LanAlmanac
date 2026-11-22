package com.gnm.resource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import com.gnm.model.PhysicalDevice;
import com.gnm.model.Telemetry;
import com.gnm.model.Telemetry.TelemetryId;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TelemetryResourceTest {

    private UUID createdDeviceId;

    @AfterEach
    void cleanup() {
        if (createdDeviceId != null) {
            QuarkusTransaction.requiringNew()
                    .run(
                            () -> {
                                Telemetry.delete("id.physicalDeviceId", createdDeviceId);
                                PhysicalDevice.deleteById(createdDeviceId);
                            });
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    void testGetTelemetry() {
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            PhysicalDevice device = new PhysicalDevice();
                            device.displayName = "Telemetry Device";
                            device.firstSeen = Instant.now();
                            device.lastSeen = Instant.now();
                            device.persist();
                            createdDeviceId = device.id;
                        });

        given().when()
                .get("/api/devices/" + createdDeviceId + "/telemetry")
                .then()
                .statusCode(200)
                .body("size()", is(0));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    void testGetTelemetryHoursFilter() {
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            PhysicalDevice device = new PhysicalDevice();
                            device.displayName = "Telemetry Device Filter";
                            device.firstSeen = Instant.now();
                            device.lastSeen = Instant.now();
                            device.persist();
                            createdDeviceId = device.id;

                            // 1. Old telemetry: 48 hours ago
                            Telemetry oldPoint = new Telemetry();
                            oldPoint.id =
                                    new TelemetryId(
                                            Instant.now().minus(Duration.ofHours(48)),
                                            device.id,
                                            "cpu_load_1m");
                            oldPoint.value = 1.5;
                            oldPoint.persist();

                            // 2. Recent telemetry: 1 hour ago
                            Telemetry recentPoint = new Telemetry();
                            recentPoint.id =
                                    new TelemetryId(
                                            Instant.now().minus(Duration.ofHours(1)),
                                            device.id,
                                            "cpu_load_1m");
                            recentPoint.value = 2.0;
                            recentPoint.persist();
                        });

        // Default / 24-hour filter should return only recentPoint
        given().when()
                .get("/api/devices/" + createdDeviceId + "/telemetry?hours=24")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].value", is(2.0f));

        // 72-hour filter should return both points
        given().when()
                .get("/api/devices/" + createdDeviceId + "/telemetry?hours=72")
                .then()
                .statusCode(200)
                .body("size()", is(2));
    }
}
