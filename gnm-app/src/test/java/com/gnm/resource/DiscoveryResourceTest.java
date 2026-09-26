package com.gnm.resource;

import com.gnm.AbstractE2ETest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import jakarta.ws.rs.core.MediaType;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class DiscoveryResourceTest extends AbstractE2ETest {

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testGetModulesList() {
        given()
          .when().get("/api/discovery/modules")
          .then()
             .statusCode(200)
             .body("size()", greaterThanOrEqualTo(3))
             .body("id", hasItems("passive-sniffer", "active-arp-scanner", "icmp-sweeper"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testToggleModule() {
        given()
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"enabled\": false}")
          .when().post("/api/discovery/modules/active-arp-scanner/toggle")
          .then()
             .statusCode(200)
             .body("enabled", is(false))
             .body("status", is("DISABLED"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testTriggerScan() {
        given()
          .contentType(MediaType.APPLICATION_JSON)
          .when().post("/api/discovery/modules/icmp-sweeper/trigger")
          .then()
             .statusCode(202);
    }
}
