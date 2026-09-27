package com.gnm.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class VersionResourceTest {

    @Test
    public void testGetVersion() {
        given()
            .when().get("/api/version")
            .then()
                .statusCode(200)
                .body("status", is("UP"))
                .body("version", notNullValue());
    }
}
