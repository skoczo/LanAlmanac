package com.gnm.pkgmgmt.patching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.client.DeviceLockManager;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.PkgWorkerClient.AuthenticatedCallResult;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchExecSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchStreamFrame;
import com.gnm.pkgmgmt.inventory.InventoryService;
import com.gnm.pkgmgmt.profile.OsDiscoveryService;
import com.gnm.pkgmgmt.profile.OsProfile;
import com.gnm.pkgmgmt.updates.DeviceAvailableUpdate;
import com.gnm.pkgmgmt.updates.UpdateCheckService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PatchJobServiceTest {

    private static PhysicalDevice buildDeviceWithProfile() {
        OsProfile profile = new OsProfile();
        profile.id = UUID.randomUUID();
        profile.slug = "debian-apt";
        profile.environmentVars = Map.of("LC_ALL", "C");
        profile.patchExecSpec =
                new PatchExecSpec(
                        null,
                        "apt-get update -qq",
                        "apt-get -y upgrade",
                        null,
                        "apt-get -y install --only-upgrade -- {{packages}}",
                        "test -f /var/run/reboot-required",
                        List.of(0),
                        true,
                        Map.of("DEBIAN_FRONTEND", "noninteractive"),
                        1800,
                        300);

        PhysicalDevice d = new PhysicalDevice();
        d.id = UUID.randomUUID();
        d.displayName = "app-node-01";
        d.osProfile = profile;
        return d;
    }

    @Test
    void rejectsCommandInjectionAndUnlistedSelectedPackages() {
        PkgWorkerClient workerClient = mock(PkgWorkerClient.class);
        OsDiscoveryService discoveryService = mock(OsDiscoveryService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        UpdateCheckService updateCheckService = mock(UpdateCheckService.class);

        PatchJobService service =
                new PatchJobService(
                        new DeviceLockManager(),
                        workerClient,
                        discoveryService,
                        inventoryService,
                        updateCheckService,
                        new PatchJobLogBroker());

        PhysicalDevice device = buildDeviceWithProfile();

        // Command injection tokens -> rejected immediately
        for (String bad : List.of("; id", "$(whoami)", "--allow-unauthenticated", "pkg1 && reboot")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            PatchJobService.validateRequestedPackages(
                                    "SELECTED_PACKAGES",
                                    List.of(bad),
                                    Set.of("openssl", "curl")));
        }

        // Valid package syntax, but not in device_available_update -> rejected
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PatchJobService.validateRequestedPackages(
                                "SELECTED_PACKAGES",
                                List.of("unlisted-pkg"),
                                Set.of("openssl", "curl")));

        // Listed valid package -> accepted
        PatchJobService.validateRequestedPackages(
                "SELECTED_PACKAGES", List.of("openssl"), Set.of("openssl", "curl"));
        assertNotNull(device);
    }

    private static void assertNotNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNotNull(o);
    }

    @Test
    @SuppressWarnings("unchecked")
    void executesStreamingJobRecordsAuditLogsAndTriggersPostPatchInventory() {
        PkgWorkerClient workerClient = mock(PkgWorkerClient.class);
        OsDiscoveryService discoveryService = mock(OsDiscoveryService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        UpdateCheckService updateCheckService = mock(UpdateCheckService.class);
        PatchJobLogBroker logBroker = new PatchJobLogBroker();

        PatchJobService service =
                new PatchJobService(
                        new DeviceLockManager(),
                        workerClient,
                        discoveryService,
                        inventoryService,
                        updateCheckService,
                        logBroker);

        PhysicalDevice device = buildDeviceWithProfile();
        UUID credId = UUID.randomUUID();

        doAnswer(
                        inv -> {
                            Consumer<PatchStreamFrame> consumer = inv.getArgument(8);
                            consumer.accept(
                                    new PatchStreamFrame(
                                            "PHASE",
                                            1,
                                            "2026-11-19T12:00:00Z",
                                            "REFRESH",
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null));
                            consumer.accept(
                                    new PatchStreamFrame(
                                            "LOG",
                                            2,
                                            "2026-11-19T12:00:01Z",
                                            null,
                                            "STDOUT",
                                            "Hit:1 bookworm InRelease",
                                            null,
                                            null,
                                            null,
                                            null,
                                            null));
                            consumer.accept(
                                    new PatchStreamFrame(
                                            "PHASE",
                                            3,
                                            "2026-11-19T12:00:02Z",
                                            "UPGRADE",
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null));
                            consumer.accept(
                                    new PatchStreamFrame(
                                            "LOG",
                                            4,
                                            "2026-11-19T12:00:03Z",
                                            null,
                                            "STDOUT",
                                            "Setting up libssl3 (3.0.11-1~deb12u2) ...",
                                            null,
                                            null,
                                            null,
                                            null,
                                            null));
                            consumer.accept(
                                    new PatchStreamFrame(
                                            "RESULT",
                                            5,
                                            "2026-11-19T12:00:04Z",
                                            null,
                                            null,
                                            null,
                                            "SUCCEEDED",
                                            0,
                                            true,
                                            null,
                                            null));
                            return new AuthenticatedCallResult<Void>(
                                    null,
                                    credId,
                                    "lanalmanac-backup",
                                    true,
                                    "PKG_MANAGEMENT_FALLBACK");
                        })
                .when(workerClient)
                .executePatchStreaming(
                        any(), any(), any(), any(), any(), any(), any(), any(), any());

        DeviceAvailableUpdate avail = new DeviceAvailableUpdate();
        avail.packageName = "libssl3";

        List<PatchJobLog> persistedLogs = new ArrayList<>();
        PatchJob job =
                service.executeJobInMemory(
                        device,
                        "SELECTED_PACKAGES",
                        List.of("libssl3"),
                        "admin",
                        List.of(avail),
                        persistedLogs);

        assertEquals("SUCCEEDED", job.status);
        assertEquals(credId, job.credentialId);
        assertEquals("lanalmanac-backup", job.credentialUsername);
        assertTrue(job.usedFallback);
        assertTrue(Boolean.TRUE.equals(job.rebootRequired));
        assertTrue(device.rebootRequired);
        assertEquals(4, persistedLogs.size());

        // Test SSE replay from afterSeq=2 via PatchJobLogBroker
        List<PatchJobLog> replayed = new ArrayList<>();
        var sub = logBroker.subscribeWithReplay(job.id, 2L, persistedLogs, replayed::add);
        logBroker.unsubscribe(job.id, sub);
        assertEquals(2, replayed.size());
        assertEquals(3L, replayed.get(0).seq);
        assertEquals(4L, replayed.get(1).seq);

        verify(inventoryService).scanDevice(device.id);
    }
}
