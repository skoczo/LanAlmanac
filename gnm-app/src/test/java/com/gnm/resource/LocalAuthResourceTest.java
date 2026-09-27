package com.gnm.resource;

import com.gnm.auth.PasswordService;
import com.gnm.model.GnmUser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class LocalAuthResourceTest {

    @Inject
    PasswordService passwordService;

    @BeforeEach
    @Transactional
    public void setup() {
        GnmUser.deleteAll();

        GnmUser user = new GnmUser();
        user.username = "testuser";
        user.passwordHash = passwordService.hashPassword("Password123!");
        user.displayName = "Test User";
        user.role = "gnm-operator";
        user.enabled = true;
        user.mustChangePassword = false;
        user.persist();
    }

    @Test
    public void testLoginSuccess() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "username", "testuser",
                "password", "Password123!"
            ))
            .when().post("/api/auth/login")
            .then()
                .statusCode(200)
                .body("token", notNullValue())
                .body("username", is("testuser"))
                .body("roles", hasItem("gnm-operator"));
    }

    @Test
    public void testLoginInvalidCredentials() {
        // Wrong password
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("username", "testuser", "password", "wrongpass"))
            .when().post("/api/auth/login")
            .then()
                .statusCode(401);

        // Missing fields
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("username", "testuser"))
            .when().post("/api/auth/login")
            .then()
                .statusCode(400);
    }

    @Test
    public void testChangePasswordSuccess() {
        // First login to get token
        String token = given()
            .contentType(ContentType.JSON)
            .body(Map.of("username", "testuser", "password", "Password123!"))
            .when().post("/api/auth/login")
            .then()
                .statusCode(200)
                .extract().path("token");

        // Change password
        given()
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(Map.of(
                "currentPassword", "Password123!",
                "newPassword", "NewPassword456!"
            ))
            .when().post("/api/auth/change-password")
            .then()
                .statusCode(200)
                .body("token", notNullValue())
                .body("mustChangePassword", is(false));
    }
}
