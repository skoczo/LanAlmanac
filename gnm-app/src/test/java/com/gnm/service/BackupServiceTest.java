package com.gnm.service;

import com.gnm.dto.backup.LanAlmanacBackup;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.DeviceType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class BackupServiceTest {

    @Inject
    BackupService backupService;

    @Test
    @Transactional
    void testExportAndImportData() {
        PhysicalDevice.deleteAll();

        PhysicalDevice device = new PhysicalDevice();
        device.displayName = "Backup Test Device";
        device.deviceType = DeviceType.SERVER;
        device.status = DeviceStatus.ONLINE;
        device.firstSeen = Instant.now();
        device.lastSeen = Instant.now();
        device.persist();

        // Export with secrets
        LanAlmanacBackup backupWithSecrets = backupService.exportData(true);
        assertNotNull(backupWithSecrets);
        assertEquals("4", backupWithSecrets.version);
        assertFalse(backupWithSecrets.devices.isEmpty());

        // Export without secrets
        LanAlmanacBackup backupNoSecrets = backupService.exportData(false);
        assertNotNull(backupNoSecrets);

        // Import backup
        backupService.importData(backupWithSecrets);
        assertEquals(1, PhysicalDevice.count());
    }

    @Test
    void testCreateBackup() throws Exception {
        Path keysDir = Paths.get("keys");
        if (!Files.exists(keysDir)) {
            Files.createDirectories(keysDir);
        }
        Path dummyKey = keysDir.resolve("dummy.key");
        Files.writeString(dummyKey, "dummy_content");

        try {
            Path encryptedBackup = backupService.createBackup("test_password123");
            assertTrue(Files.exists(encryptedBackup));
            assertTrue(Files.size(encryptedBackup) > 0);

            // Test restoring with incorrect password fails
            assertThrows(IllegalArgumentException.class, () -> {
                backupService.restoreBackup(encryptedBackup, "wrong_password");
            });

            Files.deleteIfExists(encryptedBackup);
        } finally {
            Files.deleteIfExists(dummyKey);
        }
    }
}
