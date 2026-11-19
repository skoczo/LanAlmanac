package com.gnm.pkgmgmt.schedule;

import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.pkgmgmt.client.CredentialResolver;
import com.gnm.pkgmgmt.client.DeviceLockManager;
import com.gnm.pkgmgmt.inventory.InventoryService;
import com.gnm.pkgmgmt.patching.PatchJobService;
import com.gnm.pkgmgmt.updates.UpdateCheckService;
import com.gnm.service.VaultEngine;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PackageManagementScheduler {

    private static final Logger LOG = Logger.getLogger(PackageManagementScheduler.class);

    private final InventoryService inventoryService;
    private final UpdateCheckService updateCheckService;
    private final PatchJobService patchJobService;
    private final VaultEngine vaultEngine;
    private final DeviceLockManager lockManager;
    private final CronScheduleCalculator cronCalculator;
    private final Semaphore concurrencySemaphore;

    @Inject
    public PackageManagementScheduler(
            InventoryService inventoryService,
            UpdateCheckService updateCheckService,
            PatchJobService patchJobService,
            VaultEngine vaultEngine,
            DeviceLockManager lockManager,
            CronScheduleCalculator cronCalculator,
            @ConfigProperty(name = "gnm.pkgmgmt.scheduler.max-concurrent-hosts", defaultValue = "4")
                    int maxConcurrentHosts) {
        this.inventoryService = inventoryService;
        this.updateCheckService = updateCheckService;
        this.patchJobService = patchJobService;
        this.vaultEngine = vaultEngine;
        this.lockManager = lockManager;
        this.cronCalculator = cronCalculator;
        this.concurrencySemaphore = new Semaphore(Math.max(1, maxConcurrentHosts));
    }

    public PackageManagementScheduler(
            InventoryService inventoryService,
            UpdateCheckService updateCheckService,
            PatchJobService patchJobService,
            VaultEngine vaultEngine,
            DeviceLockManager lockManager,
            CronScheduleCalculator cronCalculator) {
        this(
                inventoryService,
                updateCheckService,
                patchJobService,
                vaultEngine,
                lockManager,
                cronCalculator,
                4);
    }

    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    public void tick() {
        if (!vaultEngine.isUnsealed()) {
            return;
        }
        List<DevicePackageSchedule> schedules = DevicePackageSchedule.listAll();
        processSchedules(schedules, Instant.now());
    }

    public void processSchedules(List<DevicePackageSchedule> schedules, Instant now) {
        if (!vaultEngine.isUnsealed()) {
            return;
        }

        for (DevicePackageSchedule s : schedules) {
            PhysicalDevice device = s.device;
            if (device == null) {
                continue;
            }
            boolean trusted =
                    CredentialResolver.findSshService(device)
                            .map((NetworkService svc) -> Boolean.TRUE.equals(svc.sshHostKeyTrusted))
                            .orElse(false);
            boolean eligible =
                    device.status != DeviceStatus.OFFLINE
                            && trusted
                            && !lockManager.isLocked(device.id);

            // 1. Inventory scan
            if (s.inventoryEnabled
                    && s.nextInventoryRunAt != null
                    && !s.nextInventoryRunAt.isAfter(now)) {
                s.nextInventoryRunAt =
                        cronCalculator.nextExecution(s.inventoryCron, s.timezone, now);
                if (eligible && concurrencySemaphore.tryAcquire()) {
                    try {
                        s.lastInventoryRunAt = now;
                        inventoryService.scanDevice(device.id);
                    } catch (Exception e) {
                        LOG.warnf(
                                "Scheduled inventory scan failed for device %s: %s",
                                device.id, e.getMessage());
                    } finally {
                        concurrencySemaphore.release();
                    }
                }
            }

            // 2. Update check
            if (s.updateCheckEnabled
                    && s.nextUpdateCheckRunAt != null
                    && !s.nextUpdateCheckRunAt.isAfter(now)) {
                s.nextUpdateCheckRunAt =
                        cronCalculator.nextExecution(s.updateCheckCron, s.timezone, now);
                if (eligible && concurrencySemaphore.tryAcquire()) {
                    try {
                        s.lastUpdateCheckRunAt = now;
                        updateCheckService.checkDeviceUpdates(device.id, false);
                    } catch (Exception e) {
                        LOG.warnf(
                                "Scheduled update check failed for device %s: %s",
                                device.id, e.getMessage());
                    } finally {
                        concurrencySemaphore.release();
                    }
                }
            }

            // 3. Opt-in Auto-Patch (ADR-5)
            if (s.autoPatchEnabled
                    && s.nextAutoPatchRunAt != null
                    && !s.nextAutoPatchRunAt.isAfter(now)) {
                s.nextAutoPatchRunAt =
                        cronCalculator.nextExecution(s.autoPatchCron, s.timezone, now);
                if (eligible && concurrencySemaphore.tryAcquire()) {
                    try {
                        s.lastAutoPatchRunAt = now;
                        String actor =
                                "schedule:"
                                        + (s.updatedBy != null && !s.updatedBy.isBlank()
                                                ? s.updatedBy
                                                : "system");
                        String mode =
                                "FULL_UPGRADE".equalsIgnoreCase(s.autoPatchMode)
                                        ? "FULL_UPGRADE"
                                        : "SECURITY_ONLY";
                        patchJobService.startAsyncPatchJob(device.id, mode, List.of(), actor);
                    } catch (Exception e) {
                        LOG.warnf(
                                "Scheduled auto-patch failed for device %s: %s",
                                device.id, e.getMessage());
                    } finally {
                        concurrencySemaphore.release();
                    }
                }
            }
        }
    }
}
