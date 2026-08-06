package com.gnm.resource;

import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.DeviceType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class DeviceResourceTest {

    @BeforeEach
    @Transactional
    public void setup() {
        PhysicalDevice.deleteAll();
        
        PhysicalDevice device = new PhysicalDevice();
        device.displayName = "Test Device";
        device.deviceType = DeviceType.ROUTER;
        device.firstSeen = Instant.now();
        device.lastSeen = Instant.now();
        device.status = DeviceStatus.ONLINE;
        device.persist();
    }

    @Test
    public void testGetAllDevicesWithoutAuth() {
        given()
          .when().get("/api/devices")
          .then()
             .statusCode(401);
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testGetAllDevicesWithAuth() {
        given()
          .when().get("/api/devices")
          .then()
             .statusCode(200)
             .body("size()", is(1))
             .body("[0].displayName", is("Test Device"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @Transactional
    public void testGetDeviceById() {
        PhysicalDevice device = PhysicalDevice.findAll().firstResult();
        
        given()
          .when().get("/api/devices/" + device.id)
          .then()
             .statusCode(200)
             .body("displayName", is("Test Device"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testGetDeviceByIdNotFound() {
        given()
          .when().get("/api/devices/00000000-0000-0000-0000-000000000000")
          .then()
             .statusCode(404);
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @Transactional
    public void testUpdateDeviceDetails() {
        PhysicalDevice device = PhysicalDevice.findAll().firstResult();
        
        given()
          .contentType(ContentType.JSON)
          .body(Map.of("displayName", "Updated Name", "deviceType", "SWITCH", "manufacturer", "Cisco"))
          .when().put("/api/devices/" + device.id)
          .then()
             .statusCode(200)
             .body("displayName", is("Updated Name"))
             .body("deviceType", is("SWITCH"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @Transactional
    public void testUpdateDeviceState() {
        PhysicalDevice device = PhysicalDevice.findAll().firstResult();
        
        given()
          .contentType(ContentType.JSON)
          .body(Map.of("managementState", "MANAGED"))
          .when().put("/api/devices/" + device.id + "/state")
          .then()
             .statusCode(200)
             .body("managementState", is("MANAGED"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @Transactional
    public void testUpdateDeviceLabels() {
        PhysicalDevice device = PhysicalDevice.findAll().firstResult();
        
        given()
          .contentType(ContentType.JSON)
          .body(List.of("core", "router"))
          .when().put("/api/devices/" + device.id + "/labels")
          .then()
             .statusCode(200)
             .body("labels", hasItems("core", "router"));
    }
}
