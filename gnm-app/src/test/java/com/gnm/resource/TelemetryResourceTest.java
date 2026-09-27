package com.gnm.resource;

import com.gnm.model.PhysicalDevice;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class TelemetryResourceTest {

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @Transactional
    public void testGetTelemetry() {
        PhysicalDevice device = new PhysicalDevice();
        device.displayName = "Telemetry Device";
        device.firstSeen = Instant.now();
        device.lastSeen = Instant.now();
        device.persist();

        given()
            .when().get("/api/devices/" + device.id + "/telemetry")
            .then()
                .statusCode(200)
                .body("size()", is(0));
    }
}
