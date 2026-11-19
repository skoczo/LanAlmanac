package com.gnm.pkgmgmt.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.client.DeviceLockManager;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.PkgWorkerClient.AuthenticatedCallResult;
import com.gnm.pkgmgmt.client.PkgWorkerException;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventoryResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventorySpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.NormalizedPackage;
import com.gnm.pkgmgmt.profile.OsDiscoveryService;
import com.gnm.pkgmgmt.profile.OsProfile;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.VulnerabilitySpec;
import jakarta.enterprise.event.Event;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryServiceTest {

    private static OsProfile debianProfile() {
        OsProfile p = new OsProfile();
        p.id = UUID.randomUUID();
        p.slug = "debian-apt";
        p.environmentVars = Map.of("LC_ALL", "C");
        p.inventorySpec =
                new InventorySpec(
                        "dpkg-query -W",
                        false,
                        60,
                        "DELIMITED",
                        Map.of(),
                        "pkg:deb/debian/{{name}}@{{version}}?arch={{arch}}");
        p.vulnerabilitySpec =
                new VulnerabilitySpec(true, "Debian:{{os.version_major}}", "SOURCE_PACKAGE");
        return p;
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstScanUpsertsPackagesAndSecondScanUpdatesAndRemovesUninstalled() {
        PkgWorkerClient workerClient = mock(PkgWorkerClient.class);
        OsDiscoveryService discoveryService = mock(OsDiscoveryService.class);
        Event<InventoryUpdatedEvent> eventBus = mock(Event.class);

        InventoryService service =
                new InventoryService(
                        new DeviceLockManager(), workerClient, discoveryService, eventBus);

        OsProfile profile = debianProfile();
        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();
        device.osFacts = Map.of("os.id", "debian", "os.version_major", "12");
        when(discoveryService.detectAndAssign(device)).thenReturn(profile);

        List<NormalizedPackage> scan1Pkgs =
                List.of(
                        new NormalizedPackage(
                                "libssl3",
                                "3.0.11-1~deb12u1",
                                "amd64",
                                "openssl",
                                "3.0.11-1~deb12u1",
                                "pkg:deb/debian/libssl3@3.0.11-1~deb12u1?arch=amd64"),
                        new NormalizedPackage(
                                "telnet",
                                "0.17-44",
                                "amd64",
                                "netkit-telnet",
                                "0.17-44",
                                "pkg:deb/debian/telnet@0.17-44?arch=amd64"));

        when(workerClient.collectInventory(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new AuthenticatedCallResult<>(
                                new InventoryResponse(scan1Pkgs, 2, 0, List.of(), 120),
                                UUID.randomUUID(),
                                "lanalmanac",
                                false,
                                "PKG_MANAGEMENT"));

        List<DevicePackage> stored = new ArrayList<>();
        DevicePackageScan scan1 = service.executeScanInMemory(device, stored);

        assertEquals("SUCCEEDED", scan1.status);
        assertEquals(2, scan1.packageCount);
        assertEquals(2, stored.size());
        assertEquals("Debian:12", stored.get(0).ecosystem);
        assertEquals("openssl", stored.get(0).sourceName);
        verify(eventBus).fireAsync(new InventoryUpdatedEvent(device.id));

        // Second scan: libssl3 upgraded, telnet removed, curl added
        List<NormalizedPackage> scan2Pkgs =
                List.of(
                        new NormalizedPackage(
                                "libssl3",
                                "3.0.11-1~deb12u2",
                                "amd64",
                                "openssl",
                                "3.0.11-1~deb12u2",
                                "pkg:deb/debian/libssl3@3.0.11-1~deb12u2?arch=amd64"),
                        new NormalizedPackage(
                                "curl",
                                "7.88.1-10+deb12u5",
                                "amd64",
                                "curl",
                                "7.88.1-10+deb12u5",
                                "pkg:deb/debian/curl@7.88.1-10+deb12u5?arch=amd64"));
        when(workerClient.collectInventory(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new AuthenticatedCallResult<>(
                                new InventoryResponse(scan2Pkgs, 2, 0, List.of(), 95),
                                UUID.randomUUID(),
                                "lanalmanac",
                                false,
                                "PKG_MANAGEMENT"));

        DevicePackageScan scan2 = service.executeScanInMemory(device, stored);
        assertEquals("SUCCEEDED", scan2.status);
        assertEquals(2, stored.size());
        assertTrue(
                stored.stream()
                        .anyMatch(
                                p ->
                                        "libssl3".equals(p.name)
                                                && "3.0.11-1~deb12u2".equals(p.version)));
        assertTrue(stored.stream().anyMatch(p -> "curl".equals(p.name)));
        assertTrue(
                stored.stream().noneMatch(p -> "telnet".equals(p.name)),
                "uninstalled package must be removed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedWorkerCallPreservesPreviousPackagesAndDoesNotFireEvent() {
        PkgWorkerClient workerClient = mock(PkgWorkerClient.class);
        OsDiscoveryService discoveryService = mock(OsDiscoveryService.class);
        Event<InventoryUpdatedEvent> eventBus = mock(Event.class);

        InventoryService service =
                new InventoryService(
                        new DeviceLockManager(), workerClient, discoveryService, eventBus);

        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();
        device.osProfile = debianProfile();
        device.osFacts = Map.of("os.id", "debian", "os.version_major", "12");

        DevicePackage existing = new DevicePackage();
        existing.id = UUID.randomUUID();
        existing.name = "bash";
        existing.version = "5.2";
        List<DevicePackage> stored = new ArrayList<>(List.of(existing));

        when(workerClient.collectInventory(any(), any(), any(), any(), any(), any()))
                .thenThrow(
                        new PkgWorkerException("COMMAND_TIMEOUT", "dpkg-query timed out", 502));

        assertThrows(
                PkgWorkerException.class, () -> service.executeScanInMemory(device, stored));
        assertEquals(1, stored.size(), "existing packages must not be wiped on failed scan");
        verify(eventBus, never()).fireAsync(any());
    }
}

