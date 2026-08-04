package com.gnm.resource;

import com.gnm.model.Credential;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.DeviceType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.gnm.service.VaultEngine;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
public class CredentialResourceTest {

    private PhysicalDevice testDevice;

    @Inject
    VaultEngine vaultEngine;

    @BeforeEach
    @Transactional
    public void setup() {
        Credential.deleteAll();
        PhysicalDevice.deleteAll();
        
        testDevice = new PhysicalDevice();
        testDevice.displayName = "Credential Target";
        testDevice.deviceType = DeviceType.SERVER;
        testDevice.firstSeen = Instant.now();
        testDevice.lastSeen = Instant.now();
        testDevice.status = DeviceStatus.ONLINE;
        testDevice.persist();
        
        // Ensure vault is unsealed for the test
        if (!vaultEngine.isInitialized()) {
            vaultEngine.initializeVault("test_passcode");
        } else if (!vaultEngine.isUnsealed()) {
            vaultEngine.unsealVault("test_passcode");
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testCreateAndListCredentials() {
        // Create credential
        given()
          .contentType(ContentType.JSON)
          .body(Map.of(
              "label", "Root Access",
              "type", "SSH_KEY",
              "username", "root",
              "secret", "secret123"
          ))
          .when().post("/api/credentials/device/" + testDevice.id)
          .then()
             .statusCode(200);

        // List credentials
        given()
          .when().get("/api/credentials/device/" + testDevice.id)
          .then()
             .statusCode(200)
             .body("size()", is(1))
             .body("[0].type", is("SSH_KEY"))
             .body("[0].username", is("root"));
    }
}
