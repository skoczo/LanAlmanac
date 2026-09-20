package com.gnm.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
public class VersionResourceTest {

    @Test
    public void testGetVersionPublic() {
        given()
          .when().get("/api/version")
          .then()
             .statusCode(200)
             .body("name", is("gnm-app"))
             .body("version", is("1.0.0"))
             .body("status", is("UP"))
             .body("timestamp", notNullValue());
    }
}
