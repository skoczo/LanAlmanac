package com.gnm.resource;

import com.gnm.model.GlobalSetting;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class SettingsResourceTest {

    @BeforeEach
    @Transactional
    public void setup() {
        GlobalSetting.deleteAll();
        
        GlobalSetting mode = new GlobalSetting();
        mode.key = "APP_MODE";
        mode.value = "DISCOVERY";
        mode.persist();
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testGetAllSettings() {
        given()
            .when().get("/api/settings")
            .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testUpdateSetting() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("value", "DETECTION"))
            .when().put("/api/settings/APP_MODE")
            .then()
                .statusCode(200)
                .body("value", is("DETECTION"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testGetInterfaces() {
        given()
            .when().get("/api/settings/interfaces")
            .then()
                .statusCode(200);
    }

    @Test
    public void testGetPublicOidcSettings() {
        given()
            .when().get("/api/settings/public/oidc")
            .then()
                .statusCode(200)
                .body("enabled", notNullValue());
    }
}
