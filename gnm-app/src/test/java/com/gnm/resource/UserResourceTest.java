package com.gnm.resource;

import com.gnm.model.GnmUser;
import com.gnm.auth.PasswordService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class UserResourceTest {

    @Inject
    PasswordService passwordService;

    @BeforeEach
    @Transactional
    void setup() {
        GnmUser.deleteAll();
        
        GnmUser admin = new GnmUser();
        admin.username = "admin";
        admin.passwordHash = passwordService.hashPassword("admin_pass123");
        admin.displayName = "System Admin";
        admin.role = "gnm-admin";
        admin.enabled = true;
        admin.mustChangePassword = false;
        admin.persist();
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    void testListUsers() {
        given()
            .when().get("/api/users")
            .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].username", is("admin"))
                .body("[0].role", is("gnm-admin"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    void testCreateUserSuccess() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "username", "operator1",
                "password", "secretpass123",
                "displayName", "Operator One",
                "role", "gnm-operator"
            ))
            .when().post("/api/users")
            .then()
                .statusCode(201)
                .body("username", is("operator1"))
                .body("role", is("gnm-operator"))
                .body("mustChangePassword", is(true));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    void testCreateUserValidationFailures() {
        // Missing username/password
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("username", "", "password", ""))
            .when().post("/api/users")
            .then()
                .statusCode(400);

        // Short password
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("username", "shortpass", "password", "123"))
            .when().post("/api/users")
            .then()
                .statusCode(400);

        // Duplicate username
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("username", "admin", "password", "password123"))
            .when().post("/api/users")
            .then()
                .statusCode(409);

        // Invalid role
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("username", "badrole", "password", "password123", "role", "SUPER_GOD"))
            .when().post("/api/users")
            .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    void testUpdateUser() {
        GnmUser user = GnmUser.findByUsername("admin");

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "displayName", "Updated Admin",
                "role", "gnm-admin",
                "enabled", true
            ))
            .when().put("/api/users/" + user.id)
            .then()
                .statusCode(200)
                .body("displayName", is("Updated Admin"));

        // Update non-existing user
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("displayName", "Nobody"))
            .when().put("/api/users/" + UUID.randomUUID())
            .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    void testDeleteUserLastAdminProtection() {
        GnmUser admin = GnmUser.findByUsername("admin");

        // Attempt deleting the only admin -> should fail
        given()
            .when().delete("/api/users/" + admin.id)
            .then()
                .statusCode(400);

        // Non-existing user delete -> 404
        given()
            .when().delete("/api/users/" + UUID.randomUUID())
            .then()
                .statusCode(404);
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    void testResetPassword() {
        GnmUser admin = GnmUser.findByUsername("admin");

        // Success reset
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("newPassword", "new_secure_password_123"))
            .when().put("/api/users/" + admin.id + "/reset-password")
            .then()
                .statusCode(200)
                .body("mustChangePassword", is(true));

        // Short password error
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("newPassword", "short"))
            .when().put("/api/users/" + admin.id + "/reset-password")
            .then()
                .statusCode(400);
    }
}
