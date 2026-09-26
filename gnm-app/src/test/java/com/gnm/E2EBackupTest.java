package com.gnm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import com.gnm.model.*;
import com.gnm.model.enums.*;
import com.gnm.service.BackupService;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class E2EBackupTest {

    @Inject
    BackupService backupService;

    @AfterEach
    @Transactional
    public void cleanup() {
        NetworkLink.deleteAll();
        Credential.deleteAll();
        NetworkIdentity.deleteAll();
        FingerprintVector.deleteAll();
        PhysicalDevice.deleteAll();
    }

    @Test
    public void testFullE2EBackupAndRestore() throws Exception {
        // 1. Full Configuration (Create devices, identities, credentials, links)
        createEntities();

        long initialDeviceCount = PhysicalDevice.count();
        assertTrue(initialDeviceCount > 0, "Should have created some devices");
        
        // Save to verify after
        List<PhysicalDevice> initialDevices = PhysicalDevice.listAll();

        // 2. Create Backup
        String password = "super_secure_password_123";
        Path backupFile = backupService.createBackup(password);
        
        assertTrue(Files.exists(backupFile), "Backup file should be created");
        
        // 3. Delete Everything
        cleanup(); // Calls the Transactional deletion
        assertEquals(0, PhysicalDevice.count(), "All devices should be deleted");

        // 4. Restore Backup
        backupService.restoreBackup(backupFile, password);

        // 5. Verification
        assertEquals(initialDeviceCount, PhysicalDevice.count(), "Device count should match after restore");
        List<PhysicalDevice> restoredDevices = PhysicalDevice.listAll();
        
        boolean foundDev1 = restoredDevices.stream().anyMatch(d -> d.displayName.equals("Core Router E2E"));
        assertTrue(foundDev1, "Core Router E2E should exist in the restored DB");
        
        long linksCount = NetworkLink.count();
        assertEquals(1, linksCount, "There should be exactly 1 network link restored");
        
        // Clean up the backup file
        Files.deleteIfExists(backupFile);
    }
    
    @Transactional
    void createEntities() {
        PhysicalDevice dev1 = new PhysicalDevice();
        dev1.displayName = "Core Router E2E";
        dev1.deviceType = DeviceType.ROUTER;
        dev1.osFamily = "Cisco IOS";
        dev1.status = DeviceStatus.ONLINE;
        dev1.managementState = ManagementState.MANAGED;
        dev1.firstSeen = Instant.now();
        dev1.lastSeen = Instant.now();
        dev1.persist();

        NetworkIdentity id1 = new NetworkIdentity();
        id1.ipAddress = "192.168.100.1";
        id1.macAddress = "00:11:22:33:44:55";
        id1.physicalDevice = dev1;
        id1.firstSeen = Instant.now();
        id1.lastSeen = Instant.now();
        id1.persist();

        Credential cred1 = new Credential();
        cred1.label = "SSH Admin";
        cred1.credentialType = CredentialType.PASSWORD;
        cred1.username = "admin";
        cred1.encryptedPayload = new byte[]{1,2,3}; // Fake
        cred1.noncePayload = new byte[]{4,5,6}; // Fake
        cred1.physicalDevice = dev1;
        cred1.createdAt = Instant.now();
        cred1.updatedAt = Instant.now();
        cred1.persist();
        
        PhysicalDevice dev2 = new PhysicalDevice();
        dev2.displayName = "Switch E2E";
        dev2.deviceType = DeviceType.SWITCH;
        dev2.osFamily = "Linux";
        dev2.status = DeviceStatus.ONLINE;
        dev2.managementState = ManagementState.IGNORED;
        dev2.firstSeen = Instant.now();
        dev2.lastSeen = Instant.now();
        dev2.persist();

        NetworkLink link = new NetworkLink();
        link.sourceDevice = dev1;
        link.targetDevice = dev2;
        link.sourceInterface = "eth0";
        link.targetInterface = "eth1";
        link.discoveryProtocol = DiscoveryProtocol.LLDP;
        link.lastVerified = Instant.now();
        link.persist();
    }
}
