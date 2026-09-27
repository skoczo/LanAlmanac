package com.gnm.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class DiagnosticsResourceTest {

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    void testGetDiagnostics() {
        given()
            .when().get("/api/diagnostics")
            .then()
                .statusCode(200)
                .body("memory", notNullValue())
                .body("threadsAndCpu", notNullValue())
                .body("pipeline", notNullValue())
                .body("database", notNullValue());
    }
}
