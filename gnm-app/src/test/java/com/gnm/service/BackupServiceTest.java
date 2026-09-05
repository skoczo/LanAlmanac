package com.gnm.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class BackupServiceTest {

    @Inject
    BackupService backupService;

    @Test
    public void testCreateBackup() throws Exception {
        // Create dummy keys directory to test inclusion
        Path keysDir = Paths.get("keys");
        if (!Files.exists(keysDir)) {
            Files.createDirectories(keysDir);
        }
        Path dummyKey = keysDir.resolve("dummy.key");
        Files.writeString(dummyKey, "dummy_content");

        try {
            // Create backup
            Path encryptedBackup = backupService.createBackup("test_password123");
            
            // Assert backup file is created
            assertTrue(Files.exists(encryptedBackup));
            assertTrue(Files.size(encryptedBackup) > 0);
            
            // Clean up
            Files.deleteIfExists(encryptedBackup);
        } finally {
            Files.deleteIfExists(dummyKey);
        }
    }
}
