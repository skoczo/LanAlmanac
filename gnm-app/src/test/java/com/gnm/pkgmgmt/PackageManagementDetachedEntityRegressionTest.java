package com.gnm.pkgmgmt;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
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
import com.gnm.pkgmgmt.vulnerability.DeviceVulnerability;
import com.gnm.pkgmgmt.vulnerability.VulnerabilityAdvisory;
import com.gnm.pkgmgmt.vulnerability.VulnerabilityAssessmentService;
import com.gnm.pkgmgmt.vulnerability.VulnerabilityProvider;
import com.gnm.pkgmgmt.vulnerability.VulnerabilityProvider.AdvisoryDetails;
import com.gnm.pkgmgmt.vulnerability.VulnerabilityProvider.AdvisoryMatch;
import com.gnm.pkgmgmt.vulnerability.VulnerabilityProvider.PackageQueryKey;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    @Inject
    VulnerabilityAssessmentService vulnerabilityService;

    @InjectMock
    PkgWorkerClient workerClient;

    @InjectMock
    VulnerabilityProvider vulnerabilityProvider;

    private UUID deviceId;
    private UUID credentialId;

    @BeforeEach
    void setup() {
        QuarkusTransaction.requiringNew().run(() -> {
            DeviceVulnerability.deleteAll();
            VulnerabilityAdvisory.deleteAll();
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

    @Test
    @DisplayName(
            "assessDevice succeeds on real database entities without detached entity or transaction timeout exceptions")
    void assessDeviceDoesNotThrowDetachedEntityOrTransactionException() {
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            PhysicalDevice device = PhysicalDevice.findById(deviceId);
                            DevicePackage pkg = new DevicePackage();
                            pkg.physicalDevice = device;
                            pkg.name = "curl";
                            pkg.version = "7.88.1-10+deb12u5";
                            pkg.arch = "amd64";
                            pkg.sourceName = "curl";
                            pkg.sourceVersion = "7.88.1-10+deb12u5";
                            pkg.ecosystem = "Debian:12";
                            pkg.purl = "pkg:deb/debian/curl@7.88.1-10+deb12u5?arch=amd64";
                            pkg.persist();
                        });

        PackageQueryKey key = new PackageQueryKey("Debian:12", "curl", "7.88.1-10+deb12u5");
        when(vulnerabilityProvider.queryBatch(any()))
                .thenReturn(
                        Map.of(
                                key,
                                List.of(new AdvisoryMatch("DSA-5680-1", "7.88.1-10+deb12u6"))));
        when(vulnerabilityProvider.fetchAdvisoryDetails("DSA-5680-1"))
                .thenReturn(
                        Optional.of(
                                new AdvisoryDetails(
                                        "DSA-5680-1",
                                        List.of("CVE-2024-4603"),
                                        "HIGH",
                                        8.5,
                                        "curl vulnerability",
                                        List.of("https://security-tracker.debian.org"),
                                        "7.88.1-10+deb12u6")));

        List<DeviceVulnerability> vulns =
                assertDoesNotThrow(() -> vulnerabilityService.assessDevice(deviceId));
        assertNotNull(vulns);
        assertEquals(1, vulns.size());
        assertEquals("curl", vulns.get(0).packageName);
        assertEquals("HIGH", vulns.get(0).advisory.severity);
        assertNotNull(vulns.get(0).id);
    }

    @Test
    @DisplayName("concurrent assessDevice invocations across fleet devices sharing advisories do not conflict")
    void concurrentAssessDeviceInvocationsDoNotConflict() throws Exception {
        UUID device2Id =
                QuarkusTransaction.requiringNew()
                        .call(
                                () -> {
                                    OsProfile profile =
                                            OsProfile.find("slug", "debian-apt").firstResult();
                                    PhysicalDevice d2 = new PhysicalDevice();
                                    d2.displayName = "watchdog-2";
                                    d2.deviceType = DeviceType.SERVER;
                                    d2.status = DeviceStatus.ONLINE;
                                    d2.firstSeen = Instant.now();
                                    d2.lastSeen = Instant.now();
                                    d2.osProfile = profile;
                                    d2.osProfilePinned = true;
                                    d2.persist();

                                    DevicePackage pkg2 = new DevicePackage();
                                    pkg2.physicalDevice = d2;
                                    pkg2.name = "curl";
                                    pkg2.version = "7.88.1-10+deb12u5";
                                    pkg2.arch = "amd64";
                                    pkg2.sourceName = "curl";
                                    pkg2.sourceVersion = "7.88.1-10+deb12u5";
                                    pkg2.ecosystem = "Debian:12";
                                    pkg2.purl = "pkg:deb/debian/curl@7.88.1-10+deb12u5?arch=amd64";
                                    pkg2.persist();
                                    return d2.id;
                                });

        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            PhysicalDevice device = PhysicalDevice.findById(deviceId);
                            DevicePackage pkg = new DevicePackage();
                            pkg.physicalDevice = device;
                            pkg.name = "curl";
                            pkg.version = "7.88.1-10+deb12u5";
                            pkg.arch = "amd64";
                            pkg.sourceName = "curl";
                            pkg.sourceVersion = "7.88.1-10+deb12u5";
                            pkg.ecosystem = "Debian:12";
                            pkg.purl = "pkg:deb/debian/curl@7.88.1-10+deb12u5?arch=amd64";
                            pkg.persist();
                        });

        PackageQueryKey key = new PackageQueryKey("Debian:12", "curl", "7.88.1-10+deb12u5");
        when(vulnerabilityProvider.queryBatch(any()))
                .thenReturn(
                        Map.of(
                                key,
                                List.of(new AdvisoryMatch("DSA-5680-1", "7.88.1-10+deb12u6"))));
        when(vulnerabilityProvider.fetchAdvisoryDetails("DSA-5680-1"))
                .thenReturn(
                        Optional.of(
                                new AdvisoryDetails(
                                        "DSA-5680-1",
                                        List.of("CVE-2024-4603"),
                                        "HIGH",
                                        8.5,
                                        "curl vulnerability",
                                        List.of("https://security-tracker.debian.org"),
                                        "7.88.1-10+deb12u6")));

        List<UUID> targets = List.of(deviceId, device2Id, deviceId, device2Id);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (UUID targetId : targets) {
                futures.add(
                        executor.submit(
                                () -> {
                                    io.quarkus.arc.Arc.container().requestContext().activate();
                                    try {
                                        return vulnerabilityService.assessDevice(targetId);
                                    } finally {
                                        io.quarkus.arc.Arc.container().requestContext().terminate();
                                    }
                                }));
            }
            for (Future<?> f : futures) {
                assertDoesNotThrow(() -> f.get());
            }
        }

        long countDev1 =
                QuarkusTransaction.requiringNew()
                        .call(() -> DeviceVulnerability.count("physicalDevice.id", deviceId));
        long countDev2 =
                QuarkusTransaction.requiringNew()
                        .call(() -> DeviceVulnerability.count("physicalDevice.id", device2Id));
        assertEquals(1, countDev1);
        assertEquals(1, countDev2);
    }

    @Test
    @DisplayName("subsequent assessDevice preserves suppression and marks resolved findings as FIXED")
    void subsequentAssessDevicePreservesSuppressionAndMarksResolvedAsFixed() {
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            PhysicalDevice device = PhysicalDevice.findById(deviceId);
                            DevicePackage pkg = new DevicePackage();
                            pkg.physicalDevice = device;
                            pkg.name = "curl";
                            pkg.version = "7.88.1-10+deb12u5";
                            pkg.arch = "amd64";
                            pkg.sourceName = "curl";
                            pkg.sourceVersion = "7.88.1-10+deb12u5";
                            pkg.ecosystem = "Debian:12";
                            pkg.purl = "pkg:deb/debian/curl@7.88.1-10+deb12u5?arch=amd64";
                            pkg.persist();
                        });

        PackageQueryKey key = new PackageQueryKey("Debian:12", "curl", "7.88.1-10+deb12u5");
        when(vulnerabilityProvider.queryBatch(any()))
                .thenReturn(
                        Map.of(
                                key,
                                List.of(new AdvisoryMatch("DSA-5680-1", "7.88.1-10+deb12u6"))));
        when(vulnerabilityProvider.fetchAdvisoryDetails("DSA-5680-1"))
                .thenReturn(
                        Optional.of(
                                new AdvisoryDetails(
                                        "DSA-5680-1",
                                        List.of("CVE-2024-4603"),
                                        "HIGH",
                                        8.5,
                                        "curl vulnerability",
                                        List.of("https://security-tracker.debian.org"),
                                        "7.88.1-10+deb12u6")));

        // 1. Initial assessment -> OPEN
        List<DeviceVulnerability> vulns1 = vulnerabilityService.assessDevice(deviceId);
        assertEquals(1, vulns1.size());
        assertEquals("OPEN", vulns1.get(0).status);

        // 2. Mark ACCEPTED_RISK
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            DeviceVulnerability v =
                                    DeviceVulnerability.find("physicalDevice.id", deviceId)
                                            .firstResult();
                            v.status = "ACCEPTED_RISK";
                            v.suppressionNote = "Known false positive in our airgapped env";
                        });

        // 3. Re-scan while package is still vulnerable -> ACCEPTED_RISK must be preserved!
        List<DeviceVulnerability> vulns2 = vulnerabilityService.assessDevice(deviceId);
        assertEquals(1, vulns2.size());
        assertEquals("ACCEPTED_RISK", vulns2.get(0).status);
        assertEquals(
                "Known false positive in our airgapped env",
                vulns2.get(0).suppressionNote);

        // 4. Update package to patched version in DB, queryBatch returns no matches for patched package
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            DevicePackage pkg =
                                    DevicePackage.find("physicalDevice.id", deviceId).firstResult();
                            pkg.version = "7.88.1-10+deb12u6";
                        });
        when(vulnerabilityProvider.queryBatch(any())).thenReturn(Map.of());

        // 5. Re-scan -> vulnerability must transition to FIXED
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            DeviceVulnerability v =
                                    DeviceVulnerability.find("physicalDevice.id", deviceId)
                                            .firstResult();
                            v.status = "OPEN"; // restore to OPEN to verify auto-FIXED logic
                        });

        List<DeviceVulnerability> vulns3 = vulnerabilityService.assessDevice(deviceId);
        assertEquals(1, vulns3.size());
        assertEquals("FIXED", vulns3.get(0).status);
        assertNotNull(vulns3.get(0).resolvedAt);
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @DisplayName("triggerVulnerabilityScan REST endpoint returns DTOs and updates package open vulnerability count")
    void triggerVulnerabilityScanRestEndpointReturnsDtosAndUpdatesDevicePackageCount() {
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            PhysicalDevice device = PhysicalDevice.findById(deviceId);
                            DevicePackage pkg = new DevicePackage();
                            pkg.physicalDevice = device;
                            pkg.name = "curl";
                            pkg.version = "7.88.1-10+deb12u5";
                            pkg.arch = "amd64";
                            pkg.sourceName = "curl";
                            pkg.sourceVersion = "7.88.1-10+deb12u5";
                            pkg.ecosystem = "Debian:12";
                            pkg.purl = "pkg:deb/debian/curl@7.88.1-10+deb12u5?arch=amd64";
                            pkg.persist();
                        });

        PackageQueryKey key = new PackageQueryKey("Debian:12", "curl", "7.88.1-10+deb12u5");
        when(vulnerabilityProvider.queryBatch(any()))
                .thenReturn(
                        Map.of(
                                key,
                                List.of(new AdvisoryMatch("DSA-5680-1", "7.88.1-10+deb12u6"))));
        when(vulnerabilityProvider.fetchAdvisoryDetails("DSA-5680-1"))
                .thenReturn(
                        Optional.of(
                                new AdvisoryDetails(
                                        "DSA-5680-1",
                                        List.of("CVE-2024-4603"),
                                        "HIGH",
                                        8.5,
                                        "curl vulnerability",
                                        List.of("https://security-tracker.debian.org"),
                                        "7.88.1-10+deb12u6")));

        // REST POST to trigger scan
        given()
                .contentType(ContentType.JSON)
                .when()
                .post("/api/devices/{id}/vulnerabilities/scan", deviceId)
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].packageName", is("curl"))
                .body("[0].advisoryId", is("DSA-5680-1"))
                .body("[0].severity", is("HIGH"));

        // REST GET to list device vulnerabilities
        given()
                .when()
                .get("/api/devices/{id}/vulnerabilities", deviceId)
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].packageName", is("curl"))
                .body("[0].status", is("OPEN"));

        // REST GET packages with vulnerableOnly=true must show openVulnerabilityCount = 1
        given()
                .when()
                .get("/api/devices/{id}/packages?vulnerableOnly=true", deviceId)
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].name", is("curl"))
                .body("[0].openVulnerabilityCount", is(1));
    }
}
