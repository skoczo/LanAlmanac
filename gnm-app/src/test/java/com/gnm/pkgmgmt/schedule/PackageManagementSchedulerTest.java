package com.gnm.pkgmgmt.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.pkgmgmt.client.DeviceLockManager;
import com.gnm.pkgmgmt.inventory.InventoryService;
import com.gnm.pkgmgmt.patching.PatchJobService;
import com.gnm.pkgmgmt.updates.UpdateCheckService;
import com.gnm.service.VaultEngine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PackageManagementSchedulerTest {

    private static PhysicalDevice createDevice(DeviceStatus status, boolean trusted) {
        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();
        device.status = status;
        NetworkService ssh = new NetworkService();
        ssh.protocol = "TCP";
        ssh.port = 22;
        ssh.serviceType = "SSH";
        ssh.sshHostKeyTrusted = trusted;
        device.services.add(ssh);
        return device;
    }

    @Test
    void triggersDueInventoryUpdateAndOptInAutoPatchAndUpdatesNextRunAt() {
        InventoryService inventoryService = mock(InventoryService.class);
        UpdateCheckService updateCheckService = mock(UpdateCheckService.class);
        PatchJobService patchJobService = mock(PatchJobService.class);
        VaultEngine vaultEngine = mock(VaultEngine.class);
        when(vaultEngine.isUnsealed()).thenReturn(true);

        DeviceLockManager lockManager = new DeviceLockManager();
        CronScheduleCalculator calculator = new CronScheduleCalculator();

        PhysicalDevice device = createDevice(DeviceStatus.ONLINE, true);

        DevicePackageSchedule schedule = new DevicePackageSchedule();
        schedule.device = device;
        schedule.timezone = "UTC";
        schedule.updatedBy = "admin";
        schedule.inventoryEnabled = true;
        schedule.inventoryCron = "0 3 * * *";
        schedule.nextInventoryRunAt = Instant.now().minusSeconds(60);
        schedule.updateCheckEnabled = true;
        schedule.updateCheckCron = "0 4 * * *";
        schedule.nextUpdateCheckRunAt = Instant.now().minusSeconds(60);
        schedule.autoPatchEnabled = true;
        schedule.autoPatchMode = "SECURITY_ONLY";
        schedule.autoPatchCron = "0 5 * * 0";
        schedule.nextAutoPatchRunAt = Instant.now().minusSeconds(60);

        PackageManagementScheduler scheduler =
                new PackageManagementScheduler(
                        inventoryService,
                        updateCheckService,
                        patchJobService,
                        vaultEngine,
                        lockManager,
                        calculator);

        scheduler.processSchedules(List.of(schedule), Instant.now());

        verify(inventoryService, times(1)).scanDevice(device.id);
        verify(updateCheckService, times(1)).checkDeviceUpdates(device.id, false);
        verify(patchJobService, times(1))
                .startAsyncPatchJob(
                        eq(device.id), eq("SECURITY_ONLY"), eq(List.of()), eq("schedule:admin"));
        assertTrue(schedule.nextInventoryRunAt.isAfter(Instant.now()));
        assertTrue(schedule.nextUpdateCheckRunAt.isAfter(Instant.now()));
        assertTrue(schedule.nextAutoPatchRunAt.isAfter(Instant.now()));
    }

    @Test
    void skipsOfflineOrUntrustedDevicesOrWhenVaultIsSealedOrDeviceIsLocked() {
        InventoryService inventoryService = mock(InventoryService.class);
        UpdateCheckService updateCheckService = mock(UpdateCheckService.class);
        PatchJobService patchJobService = mock(PatchJobService.class);
        VaultEngine vaultEngine = mock(VaultEngine.class);
        when(vaultEngine.isUnsealed()).thenReturn(true);

        DeviceLockManager lockManager = new DeviceLockManager();
        CronScheduleCalculator calculator = new CronScheduleCalculator();

        PhysicalDevice offlineDevice = createDevice(DeviceStatus.OFFLINE, true);
        DevicePackageSchedule offlineSchedule = new DevicePackageSchedule();
        offlineSchedule.device = offlineDevice;
        offlineSchedule.timezone = "UTC";
        offlineSchedule.inventoryEnabled = true;
        offlineSchedule.inventoryCron = "0 3 * * *";
        offlineSchedule.nextInventoryRunAt = Instant.now().minusSeconds(30);

        PhysicalDevice lockedDevice = createDevice(DeviceStatus.ONLINE, true);
        lockManager.tryAcquire(lockedDevice.id, Duration.ofMinutes(5));

        DevicePackageSchedule lockedSchedule = new DevicePackageSchedule();
        lockedSchedule.device = lockedDevice;
        lockedSchedule.timezone = "UTC";
        lockedSchedule.inventoryEnabled = true;
        lockedSchedule.inventoryCron = "0 3 * * *";
        lockedSchedule.nextInventoryRunAt = Instant.now().minusSeconds(30);

        PackageManagementScheduler scheduler =
                new PackageManagementScheduler(
                        inventoryService,
                        updateCheckService,
                        patchJobService,
                        vaultEngine,
                        lockManager,
                        calculator);

        scheduler.processSchedules(List.of(offlineSchedule, lockedSchedule), Instant.now());

        verify(inventoryService, never()).scanDevice(any());
        assertTrue(offlineSchedule.nextInventoryRunAt.isAfter(Instant.now()));
        assertTrue(lockedSchedule.nextInventoryRunAt.isAfter(Instant.now()));

        // When vault is sealed, nothing runs
        when(vaultEngine.isUnsealed()).thenReturn(false);
        Instant before = offlineSchedule.nextInventoryRunAt;
        scheduler.processSchedules(List.of(offlineSchedule), Instant.now());
        assertEquals(before, offlineSchedule.nextInventoryRunAt);
    }
}
