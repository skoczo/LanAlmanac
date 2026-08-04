package com.gnm.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class VaultEngineTest {

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
    public void testVaultInitializationAndUnsealing() {
        assertFalse(vaultEngine.isInitialized());
        
        vaultEngine.initializeVault("my_strong_passcode");
        assertTrue(vaultEngine.isInitialized());
        assertTrue(vaultEngine.isUnsealed());
        
        vaultEngine.lockVault();
        assertFalse(vaultEngine.isUnsealed());
        
        boolean success = vaultEngine.unsealVault("my_strong_passcode");
        assertTrue(success);
        assertTrue(vaultEngine.isUnsealed());
        
        vaultEngine.lockVault();
        boolean wrongPasscode = vaultEngine.unsealVault("wrong_passcode");
        assertFalse(wrongPasscode);
        assertFalse(vaultEngine.isUnsealed());
    }

    @Test
    public void testEncryptionAndDecryption() {
        vaultEngine.initializeVault("test_passcode");
        
        String secret = "super_secret_ssh_key";
        VaultEngine.EncryptedRecord record = vaultEngine.encrypt(secret.getBytes(StandardCharsets.UTF_8));
        
        assertNotNull(record.ciphertext);
        assertNotNull(record.iv);
        
        byte[] decrypted = vaultEngine.decrypt(record.ciphertext, record.iv);
        assertEquals(secret, new String(decrypted, StandardCharsets.UTF_8));
    }
    
    @Test
    public void testEncryptThrowsWhenSealed() {
        vaultEngine.initializeVault("test");
        vaultEngine.lockVault();
        assertThrows(IllegalStateException.class, () -> vaultEngine.encrypt("hello".getBytes()));
    }
}
