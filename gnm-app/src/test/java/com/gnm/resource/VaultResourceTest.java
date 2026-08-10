package com.gnm.resource;

import com.gnm.service.VaultEngine;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class VaultResourceTest {

    @Inject
    VaultEngine vaultEngine;

    private static final String VAULT_FILE_PATH = "keys/.vault_master";

    @BeforeEach
    public void setup() {
        File file = new File(VAULT_FILE_PATH);
        if (file.exists()) {
            file.delete();
        }
        vaultEngine.lockVault();
    }
    
    @AfterEach
    public void cleanup() {
        File file = new File(VAULT_FILE_PATH);
        if (file.exists()) {
            file.delete();
        }
    }

    @Test
    public void testVaultStatusAndLifecycle() {
        // Status should be not initialized
        given()
          .when().get("/api/vault/status")
          .then()
             .statusCode(200)
             .body("initialized", is(false))
             .body("sealed", is(true));

        // Initialize vault
        given()
          .contentType(ContentType.JSON)
          .when().post("/api/vault/init")
          .then()
             .statusCode(200)
             .body("success", is(true));
             
        // Status should be initialized and unsealed
        given()
          .when().get("/api/vault/status")
          .then()
             .statusCode(200)
             .body("initialized", is(true))
             .body("sealed", is(false));
             
        // Lock vault
        given()
          .contentType(ContentType.JSON)
          .when().post("/api/vault/lock")
          .then()
             .statusCode(200);
             
        // Status should be sealed
        given()
          .when().get("/api/vault/status")
          .then()
             .statusCode(200)
             .body("sealed", is(true));
             
        // Unseal with server password
        given()
          .contentType(ContentType.JSON)
          .when().post("/api/vault/unseal")
          .then()
             .statusCode(200);
    }
}
