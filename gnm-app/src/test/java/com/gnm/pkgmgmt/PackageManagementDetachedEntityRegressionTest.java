package com.gnm.pkgmgmt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

import com.gnm.model.Credential;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.CredentialCategory;
import com.gnm.model.enums.CredentialType;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.DeviceType;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.PkgWorkerClient.AuthenticatedCallResult;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.AvailableUpdate;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventoryResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.NormalizedPackage;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckResponse;
import com.gnm.pkgmgmt.inventory.DevicePackage;
import com.gnm.pkgmgmt.inventory.DevicePackageScan;
import com.gnm.pkgmgmt.inventory.InventoryService;
import com.gnm.pkgmgmt.profile.OsProfile;
import com.gnm.pkgmgmt.updates.DeviceAvailableUpdate;
import com.gnm.pkgmgmt.updates.UpdateCheckService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression contract test verifying that UpdateCheckService and InventoryService can safely
 * process real persisted PhysicalDevice entities across decoupled database transactions
 * without triggering Hibernate LazyInitializationException on detached collections (services, credentials, identities).
 */
@QuarkusTest
class PackageManagementDetachedEntityRegressionTest {

    @Inject
    UpdateCheckService updateCheckService;

    @Inject
    InventoryService inventoryService;

    @InjectMock
    PkgWorkerClient workerClient;

    private UUID deviceId;
    private UUID credentialId;

    @BeforeEach
    void setup() {
        QuarkusTransaction.requiringNew().run(() -> {
            DevicePackage.deleteAll();
            DeviceAvailableUpdate.deleteAll();
            DevicePackageScan.deleteAll();
            NetworkService.deleteAll();
            NetworkIdentity.deleteAll();
            Credential.deleteAll();
            PhysicalDevice.deleteAll();

            OsProfile profile = OsProfile.find("slug", "debian-apt").firstResult();

            PhysicalDevice device = new PhysicalDevice();
            device.displayName = "watchdog";
            device.deviceType = DeviceType.SERVER;
            device.status = DeviceStatus.ONLINE;
            device.firstSeen = Instant.now();
            device.lastSeen = Instant.now();
            device.osProfile = profile;
            device.osProfilePinned = true;
            device.persist();

            NetworkIdentity identity = new NetworkIdentity();
            identity.physicalDevice = device;
            identity.ipAddress = "192.168.1.186";
            identity.macAddress = "02:42:ac:11:00:99";
            identity.current = true;
            identity.firstSeen = Instant.now();
            identity.lastSeen = Instant.now();
            identity.persist();

            Credential cred = new Credential();
            cred.physicalDevice = device;
            cred.label = "ssh-cred";
            cred.username = "skoczo";
            cred.credentialType = CredentialType.PASSWORD;
            cred.encryptedPayload = new byte[] {1, 2, 3};
            cred.noncePayload = new byte[] {4, 5, 6};
            cred.createdAt = Instant.now();
            cred.updatedAt = Instant.now();
            cred.categories = Set.of(CredentialCategory.PKG_MANAGEMENT);
            cred.persist();
            credentialId = cred.id;

            NetworkService sshService = new NetworkService();
            sshService.physicalDevice = device;
            sshService.serviceType = "SSH";
            sshService.port = 22;
            sshService.firstSeen = Instant.now();
            sshService.lastSeen = Instant.now();
            sshService.sshHostKey = "SHA256:abc123trusted";
            sshService.sshHostKeyTrusted = true;
            sshService.credential = cred;
            sshService.persist();

            deviceId = device.id;
        });
    }

    @Test
    @DisplayName("checkDeviceUpdates succeeds on real database entities without LazyInitializationException")
    void checkDeviceUpdatesDoesNotThrowLazyInitializationException() {
        when(workerClient.checkUpdates(any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(
                        new AuthenticatedCallResult<>(
                                new UpdateCheckResponse(
                                        List.of(
                                                new AvailableUpdate(
                                                        "curl",
                                                        "amd64",
                                                        "7.88.1-10+deb12u5",
                                                        "7.88.1-10+deb12u6",
                                                        true)),
                                        0,
                                        1,
                                        350),
                                credentialId,
                                "skoczo",
                                false,
                                "PKG_MANAGEMENT"));

        List<DeviceAvailableUpdate> updates =
                assertDoesNotThrow(() -> updateCheckService.checkDeviceUpdates(deviceId, false));
        assertNotNull(updates);
        assertEquals(1, updates.size());
        assertEquals("curl", updates.get(0).packageName);
    }

    @Test
    @DisplayName("scanDevice succeeds on real database entities without LazyInitializationException")
    void scanDeviceDoesNotThrowLazyInitializationException() {
        when(workerClient.collectInventory(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new AuthenticatedCallResult<>(
                                new InventoryResponse(
                                        List.of(
                                                new NormalizedPackage(
                                                        "curl",
                                                        "7.88.1-10+deb12u5",
                                                        "amd64",
                                                        "curl",
                                                        "7.88.1-10+deb12u5",
                                                        "pkg:deb/debian/curl@7.88.1-10+deb12u5?arch=amd64")),
                                        1,
                                        0,
                                        List.of(),
                                        120),
                                credentialId,
                                "skoczo",
                                false,
                                "PKG_MANAGEMENT"));

        DevicePackageScan scan =
                assertDoesNotThrow(() -> inventoryService.scanDevice(deviceId));
        assertNotNull(scan);
        assertEquals("SUCCEEDED", scan.status);
    }
}
