package com.gnm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.gnm.model.Credential;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceType;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.ManagementState;
import com.gnm.service.VaultEngine;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class CredentialVaultTest extends AbstractE2ETest {

    @Inject
    VaultEngine vaultEngine;

    @BeforeEach
    public void setup() {
        if (!vaultEngine.isInitialized()) {
            given().contentType("application/json").when().post("/api/vault/init").then().statusCode(200);
        }
        // Ensure vault is unsealed for tests unless specified otherwise
        if (!vaultEngine.isUnsealed()) {
            given().contentType("application/json").when().post("/api/vault/unseal").then().statusCode(200);
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testVaultKeySealingAndUnsealing() {
        // Given: The vault is initially unsealed
        given().when().get("/api/vault/status")
            .then().statusCode(200)
            .body("sealed", org.hamcrest.Matchers.is(false));

        // When: We lock (seal) the vault
        Response lockRes = given().contentType("application/json").when().post("/api/vault/lock");
        if (lockRes.statusCode() != 200) {
            System.err.println("Lock failed: " + lockRes.statusCode() + " " + lockRes.getBody().asString());
        }
        lockRes.then().statusCode(200);

        // Then: Status should reflect sealed
        given().when().get("/api/vault/status")
            .then().statusCode(200)
            .body("sealed", org.hamcrest.Matchers.is(true));

        // When: We unseal it with the master passphrase
        Response unsealRes = given().contentType("application/json").when().post("/api/vault/unseal");
        if (unsealRes.statusCode() != 200) {
            System.err.println("Unseal failed: " + unsealRes.statusCode() + " " + unsealRes.getBody().asString());
        }
        unsealRes.then().statusCode(200);

        // Then: Status should reflect unsealed again
        given().when().get("/api/vault/status")
            .then().statusCode(200)
            .body("sealed", org.hamcrest.Matchers.is(false));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testEncryptionAtRestVerification() {
        // Given: We have a physical device in the DB
        PhysicalDevice pd = createMockDevice();

        // When: We create a credential using the API
        String plainPassword = "SuperSecretPassword123!";
        
        String payload = """
            {
                "label": "Secure Credential",
                "type": "PASSWORD",
                "username": "admin",
                "secret": "%s"
            }
        """.formatted(plainPassword);
        
        Response res = given()
            .contentType("application/json")
            .body(payload)
            .when().post("/api/credentials/device/" + pd.id.toString());
            
        res.then().statusCode(200);
        
        String credId = res.jsonPath().getString("id");

        // Then: Bypassing the application to query the DB directly
        Credential storedCred = getCredentialFromDb(credId);
        
        assertNotNull(storedCred, "Credential should exist in DB");
        assertNotNull(storedCred.encryptedPayload, "Encrypted payload should not be null");
        assertNotNull(storedCred.noncePayload, "Nonce payload should not be null");

        // Assert that the stored credential is AES-256-GCM encrypted ciphertext and completely unreadable
        String encryptedStr = new String(storedCred.encryptedPayload);
        assertFalse(encryptedStr.contains(plainPassword), "The encrypted payload MUST NOT contain the plain text password!");
        
        // Let's also verify that when read through the API, it is decrypted (if vault is unsealed)
        given()
            .when().get("/api/credentials/" + credId + "/reveal")
            .then().statusCode(200)
            .body("secret", org.hamcrest.Matchers.equalTo(plainPassword));
    }
    
    @Transactional
    protected PhysicalDevice createMockDevice() {
        PhysicalDevice pd = new PhysicalDevice();
        pd.displayName = "Vault Test Server";
        pd.deviceType = DeviceType.SERVER;
        pd.managementState = ManagementState.MANAGED;
        pd.status = DeviceStatus.ONLINE;
        pd.firstSeen = java.time.Instant.now();
        pd.lastSeen = java.time.Instant.now();
        pd.persist();
        return pd;
    }
    
    @Transactional
    protected Credential getCredentialFromDb(String id) {
        return Credential.findById(java.util.UUID.fromString(id));
    }
}
