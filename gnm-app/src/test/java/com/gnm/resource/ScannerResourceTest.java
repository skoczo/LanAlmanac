package com.gnm.resource;

import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.DeviceType;
import com.gnm.model.enums.PortScanState;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class ScannerResourceTest {

    private UUID deviceId;

    @BeforeEach
    @Transactional
    public void setup() {
        PhysicalDevice.deleteAll();

        PhysicalDevice device = new PhysicalDevice();
        device.displayName = "Scan Target";
        device.deviceType = DeviceType.SERVER;
        device.status = DeviceStatus.ONLINE;
        device.portScanState = PortScanState.PENDING;
        device.firstSeen = Instant.now();
        device.lastSeen = Instant.now();
        device.persist();

        deviceId = device.id;
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testScannerProgressAndTrigger() {
        // Get progress
        given()
            .when().get("/api/scanner/progress")
            .then()
                .statusCode(200);

        // Scan specific device
        given()
            .contentType("application/json")
            .when().post("/api/scanner/scan/" + deviceId)
            .then()
                .statusCode(202);

        // Scan non-existing device
        given()
            .contentType("application/json")
            .when().post("/api/scanner/scan/" + UUID.randomUUID())
            .then()
                .statusCode(404);

        // Scan all pending
        given()
            .contentType("application/json")
            .when().post("/api/scanner/scan-all")
            .then()
                .statusCode(202);
    }
}
