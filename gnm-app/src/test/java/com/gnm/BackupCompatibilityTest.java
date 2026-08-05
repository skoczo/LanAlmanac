package com.gnm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gnm.dto.backup.LanAlmanacBackup;
import com.gnm.service.BackupService;
import com.gnm.model.PhysicalDevice;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
public class BackupCompatibilityTest {

    @Inject
    BackupService backupService;

    @Test
    public void testBackupBackwardCompatibility() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        // Find the backups folder in test resources
        URL resource = getClass().getClassLoader().getResource("backups");
        assertNotNull(resource, "backups directory not found in src/test/resources");

        Path backupsDir = Paths.get(resource.toURI());
        
        try (Stream<Path> paths = Files.list(backupsDir)) {
            paths.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                System.out.println("Testing backup compatibility for: " + path.getFileName());

                LanAlmanacBackup backup = assertDoesNotThrow(() -> {
                    try (InputStream is = Files.newInputStream(path)) {
                        return mapper.readValue(is, LanAlmanacBackup.class);
                    }
                }, "Failed to parse JSON backup file: " + path.getFileName());

                // Import the data
                assertDoesNotThrow(() -> backupService.importData(backup), 
                    "Failed to import backup file into current schema: " + path.getFileName());
            });
        }
    }
}
