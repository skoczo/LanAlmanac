package com.gnm.resource;

import com.gnm.model.NetworkLink;
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
import static org.hamcrest.MatcherAssert.assertThat;
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

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testAddDeviceLink() {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            PhysicalDevice target = new PhysicalDevice();
            target.displayName = "Target Device";
            target.deviceType = DeviceType.SWITCH;
            target.firstSeen = Instant.now();
            target.lastSeen = Instant.now();
            target.status = DeviceStatus.ONLINE;
            target.persist();
        });

        PhysicalDevice source = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> (PhysicalDevice) PhysicalDevice.findAll().firstResult());
        PhysicalDevice target = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> (PhysicalDevice) PhysicalDevice.find("displayName", "Target Device").firstResult());

        given()
          .contentType(ContentType.JSON)
          .body(Map.of("targetDeviceId", target.id.toString()))
          .when().post("/api/devices/" + source.id + "/links")
          .then()
             .statusCode(200)
             .body("targetDevice.id", is(target.id.toString()))
             .body("discoveryProtocol", is("MANUAL"));
    }
    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testGetTopology() {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            PhysicalDevice source = new PhysicalDevice();
            source.displayName = "Topo Source";
            source.firstSeen = Instant.now();
            source.lastSeen = Instant.now();
            source.managementState = com.gnm.model.enums.ManagementState.MANAGED;
            source.persist();

            PhysicalDevice target = new PhysicalDevice();
            target.displayName = "Topo Target";
            target.firstSeen = Instant.now();
            target.lastSeen = Instant.now();
            target.managementState = com.gnm.model.enums.ManagementState.DISCOVERED;
            target.persist();

            NetworkLink link = new NetworkLink();
            link.sourceDevice = source;
            link.targetDevice = target;
            link.sourceInterface = "eth0";
            link.targetInterface = "eth1";
            link.discoveryProtocol = com.gnm.model.enums.DiscoveryProtocol.MANUAL;
            link.lastVerified = Instant.now();
            link.persist();
        });

        io.restassured.response.Response response = given()
          .contentType(ContentType.JSON)
          .when().get("/api/topology");
          
        response.then().statusCode(200);
        System.out.println("TOPOLOGY RESPONSE: " + response.getBody().asString());
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @Transactional
    public void testManualAddDevice() {
        given()
          .contentType(ContentType.JSON)
          .body(Map.of(
              "ipAddress", "192.168.1.50",
              "displayName", "Manual Server",
              "deviceType", "SERVER",
              "locationNote", "Rack 1"
          ))
          .when().post("/api/devices/manual")
          .then()
             .statusCode(201)
             .body("displayName", is("Manual Server"))
             .body("deviceType", is("SERVER"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @Transactional
    public void testDeleteDevice() {
        PhysicalDevice device = PhysicalDevice.findAll().firstResult();
        given()
          .when().delete("/api/devices/" + device.id)
          .then()
             .statusCode(204);

        given()
          .when().get("/api/devices/" + device.id)
          .then()
             .statusCode(404);
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @Transactional
    public void testDeviceServicesCrud() {
        PhysicalDevice device = PhysicalDevice.findAll().firstResult();

        // Add service
        com.gnm.model.NetworkService service = new com.gnm.model.NetworkService();
        service.port = 8080;
        service.serviceType = "HTTP";
        service.protocol = "TCP";
        service.label = "Web UI";

        given()
          .contentType(ContentType.JSON)
          .body(service)
          .when().post("/api/devices/" + device.id + "/services")
          .then()
             .statusCode(200)
             .body("port", is(8080))
             .body("serviceType", is("HTTP"));

        // Get services
        List<Map<String, Object>> services = given()
          .when().get("/api/devices/" + device.id + "/services")
          .then()
             .statusCode(200)
             .extract().body().jsonPath().getList(".");

        assertThat(services.size(), greaterThanOrEqualTo(1));
        String serviceId = (String) services.get(0).get("id");

        // Update service
        given()
          .contentType(ContentType.JSON)
          .body(Map.of("label", "Custom Web"))
          .when().put("/api/devices/" + device.id + "/services/" + serviceId)
          .then()
             .statusCode(200)
             .body("label", is("Custom Web"));

        // Delete service
        given()
          .when().delete("/api/devices/services/" + serviceId)
          .then()
             .statusCode(204);
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @Transactional
    public void testProbeUpdateAndHistories() {
        PhysicalDevice device = PhysicalDevice.findAll().firstResult();

        // Trigger probe update
        given()
          .contentType(ContentType.JSON)
          .body(List.of("192.168.1.1"))
          .when().post("/api/devices/probe-update")
          .then()
             .statusCode(202);

        // Get correlation history
        given()
          .when().get("/api/devices/" + device.id + "/correlation-history")
          .then()
             .statusCode(200);

        // Get status history
        given()
          .when().get("/api/devices/" + device.id + "/status-history")
          .then()
             .statusCode(200);

        // Get telemetry
        given()
          .when().get("/api/devices/" + device.id + "/telemetry")
          .then()
             .statusCode(200);
    }
}

