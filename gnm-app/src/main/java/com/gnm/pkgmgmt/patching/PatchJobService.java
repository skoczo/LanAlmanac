package com.gnm.pkgmgmt.patching;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.client.CredentialResolver;
import com.gnm.pkgmgmt.client.DeviceLockManager;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.PkgWorkerClient.AuthenticatedCallResult;
import com.gnm.pkgmgmt.client.PkgWorkerException;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchStreamFrame;
import com.gnm.pkgmgmt.inventory.InventoryService;
import com.gnm.pkgmgmt.profile.OsDiscoveryService;
import com.gnm.pkgmgmt.profile.OsProfile;
import com.gnm.pkgmgmt.updates.DeviceAvailableUpdate;
import com.gnm.pkgmgmt.updates.UpdateCheckService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PatchJobService {

    private static final Logger log = Logger.getLogger(PatchJobService.class);
    private static final Pattern VALID_PACKAGE_NAME_PATTERN =
            Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9.+:~_-]{0,127}$");
    private static final Set<String> VALID_MODES =
            Set.of("FULL_UPGRADE", "SECURITY_ONLY", "SELECTED_PACKAGES");

    private final DeviceLockManager lockManager;
    private final PkgWorkerClient workerClient;
    private final OsDiscoveryService discoveryService;
    private final InventoryService inventoryService;
    private final UpdateCheckService updateCheckService;
    private final PatchJobLogBroker logBroker;

    @Inject
    public PatchJobService(
            DeviceLockManager lockManager,
            PkgWorkerClient workerClient,
            OsDiscoveryService discoveryService,
            InventoryService inventoryService,
            UpdateCheckService updateCheckService,
            PatchJobLogBroker logBroker) {
        this.lockManager = lockManager;
        this.workerClient = workerClient;
        this.discoveryService = discoveryService;
        this.inventoryService = inventoryService;
        this.updateCheckService = updateCheckService;
        this.logBroker = logBroker;
    }

    @Transactional
    @SuppressWarnings("java:S6906")
    public PatchJob startAsyncPatchJob(
            UUID deviceId, String rawMode, List<String> requestedPackages, String actor) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }
        if (lockManager.isLocked(deviceId)) {
            throw new PkgWorkerException(
                    "DEVICE_BUSY",
                    "Another package operation is already running on this device",
                    409);
        }

        String mode = normalizeMode(rawMode);
        List<DeviceAvailableUpdate> available =
                DeviceAvailableUpdate.list("physicalDevice.id", deviceId);
        Set<String> availableNames =
                available.stream().map(u -> u.packageName).collect(Collectors.toSet());
        validateRequestedPackages(mode, requestedPackages, availableNames);

        OsProfile profile = resolveProfile(device);

        PatchJob job = new PatchJob();
        job.id = UUID.randomUUID();
        job.physicalDevice = device;
        job.osProfile = profile;
        job.triggeredBy = actor != null ? actor : "operator";
        job.mode = mode;
        job.requestedPackages = requestedPackages != null ? List.copyOf(requestedPackages) : List.of();
        job.status = "RUNNING";
        job.currentPhase = "PENDING";
        job.startedAt = Instant.now();
        job.createdAt = Instant.now();
        job.persist();

        UUID jobId = job.id;
        Thread.startVirtualThread(() -> runAsyncJob(deviceId, jobId));
        return job;
    }

    private void runAsyncJob(UUID deviceId, UUID jobId) {
        boolean succeeded = false;
        try {
            lockManager.withDeviceLock(
                    deviceId,
                    Duration.ofMinutes(40),
                    () -> {
                        PhysicalDevice device =
                                QuarkusTransaction.requiringNew()
                                        .call(
                                                () -> {
                                                    PhysicalDevice d = PhysicalDevice.findById(deviceId);
                                                    if (d != null) {
                                                        CredentialResolver.initializeForExecution(d);
                                                    }
                                                    return d;
                                                });
                        PatchJob job =
                                QuarkusTransaction.requiringNew()
                                        .call(() -> PatchJob.findById(jobId));
                        if (device == null || job == null) {
                            return;
                        }
                        executeStreamingCall(device, job, null, true);
                    });

            PatchJob finishedJob =
                    QuarkusTransaction.requiringNew()
                            .call(() -> PatchJob.findById(jobId));
            succeeded = finishedJob != null && ("SUCCEEDED".equalsIgnoreCase(finishedJob.status)
                    || "SUCCESS".equalsIgnoreCase(finishedJob.status));
        } catch (Exception e) {
            log.errorf(e, "Async patch job %s failed", jobId);
        } finally {
            logBroker.completeJob(jobId);
        }

        if (succeeded) {
            triggerPostPatchRefresh(deviceId, jobId);
        }
    }

    private void triggerPostPatchRefresh(UUID deviceId, UUID jobId) {
        try {
            log.infof("Post-patch refresh: scanning package inventory and checking updates for device %s", deviceId);
            appendLog(
                    jobId,
                    "SYSTEM",
                    "--- Post-Patch: Refreshing package inventory and checking available updates ---",
                    null,
                    true);
            inventoryService.scanDevice(deviceId);
            updateCheckService.checkDeviceUpdates(deviceId, false);
            appendLog(
                    jobId,
                    "SYSTEM",
                    "--- Post-Patch: Package inventory and updates refreshed successfully ---",
                    null,
                    true);
            log.infof("Post-patch refresh completed successfully for device %s", deviceId);
        } catch (Exception e) {
            log.warnf("Post-patch inventory refresh failed for device %s: %s", deviceId, e.getMessage());
            appendLog(
                    jobId,
                    "SYSTEM",
                    "Post-patch inventory refresh warning: " + e.getMessage(),
                    null,
                    true);
        }
    }

    /**
     * In-memory execution entry point for deterministic unit testing.
     */
    public PatchJob executeJobInMemory(
            PhysicalDevice device,
            String rawMode,
            List<String> requestedPackages,
            String actor,
            List<DeviceAvailableUpdate> availableUpdates,
            List<PatchJobLog> logStore) {
        String mode = normalizeMode(rawMode);
        Set<String> availableNames =
                availableUpdates == null
                        ? Set.of()
                        : availableUpdates.stream()
                                .map(u -> u.packageName)
                                .collect(Collectors.toSet());
        validateRequestedPackages(mode, requestedPackages, availableNames);

        return lockManager.withDeviceLock(
                device.id,
                Duration.ofMinutes(30),
                () -> {
                    OsProfile profile = resolveProfile(device);
                    PatchJob job = new PatchJob();
                    job.id = UUID.randomUUID();
                    job.physicalDevice = device;
                    job.osProfile = profile;
                    job.triggeredBy = actor;
                    job.mode = mode;
                    job.requestedPackages =
                            requestedPackages != null ? List.copyOf(requestedPackages) : List.of();
                    job.status = "RUNNING";
                    job.startedAt = Instant.now();

                    try {
                        executeStreamingCall(device, job, logStore, false);
                    } finally {
                        logBroker.completeJob(job.id);
                    }
                    return job;
                });
    }

    private void executeStreamingCall(
            PhysicalDevice device,
            PatchJob job,
            List<PatchJobLog> inMemoryLogStore,
            boolean persistToDb) {
        OsProfile profile = job.osProfile;
        try {
            AuthenticatedCallResult<Void> call =
                    workerClient.executePatchStreaming(
                            job.id,
                            device,
                            profile.environmentVars,
                            job.mode,
                            job.requestedPackages,
                            profile.patchExecSpec,
                            profile.privilegeSpec,
                            profile.lockSpec,
                            frame -> handleStreamFrame(device, job, frame, inMemoryLogStore, persistToDb));

            job.credentialId = call.credentialId();
            job.credentialUsername = call.credentialUsername();
            job.usedFallback = call.usedFallback();
            if (persistToDb) {
                QuarkusTransaction.requiringNew()
                        .run(
                                () -> {
                                    PatchJob managed = PatchJob.findById(job.id);
                                    if (managed != null) {
                                        managed.credentialId = call.credentialId();
                                        managed.credentialUsername = call.credentialUsername();
                                        managed.usedFallback = call.usedFallback();
                                        managed.persist();
                                    }
                                });
            }

            if ("SUCCEEDED".equals(job.status)) {
                try {
                    inventoryService.scanDevice(device.id);
                } catch (Exception scanEx) {
                    log.warnf(
                            scanEx,
                            "Post-patch inventory refresh failed for device %s",
                            device.id);
                }
            } else if ("CONNECTION_LOST".equals(job.status)) {
                scheduleDelayedRecheck(device.id);
            }
        } catch (PkgWorkerException e) {
            job.status = "FAILED";
            job.errorCode = e.errorCode();
            job.errorSummary = e.getMessage();
            job.finishedAt = Instant.now();
            appendLog(
                    job.id,
                    "STDERR",
                    "ERROR [" + e.errorCode() + "]: " + e.getMessage(),
                    inMemoryLogStore,
                    persistToDb);
            if (persistToDb) {
                persistJobState(job, device);
            }
        }
    }

    private void handleStreamFrame(
            PhysicalDevice device,
            PatchJob job,
            PatchStreamFrame frame,
            List<PatchJobLog> inMemoryLogStore,
            boolean persistToDb) {
        if (frame == null || frame.type() == null) {
            return;
        }
        switch (frame.type()) {
            case "PHASE" -> {
                job.currentPhase = frame.phase();
                appendLog(
                        job.id,
                        "SYSTEM",
                        "--- Phase: " + frame.phase() + " ---",
                        inMemoryLogStore,
                        persistToDb);
                if (persistToDb) {
                    persistJobState(job, device);
                }
            }
            case "LOG" ->
                    appendLog(
                            job.id,
                            frame.stream(),
                            frame.line(),
                            inMemoryLogStore,
                            persistToDb);
            case "RESULT" -> {
                job.status = frame.status() != null ? frame.status() : "FAILED";
                job.exitCode = frame.exitCode();
                job.rebootRequired = frame.rebootRequired();
                job.errorCode = frame.errorCode();
                job.errorSummary = frame.errorMessage();
                job.finishedAt = Instant.now();
                if (frame.rebootRequired() != null) {
                    device.rebootRequired = frame.rebootRequired();
                }
                if (persistToDb) {
                    persistJobState(job, device);
                }
            }
            default -> {}
        }
    }

    private void appendLog(
            UUID jobId,
            String stream,
            String line,
            List<PatchJobLog> inMemoryLogStore,
            boolean persistToDb) {
        PatchJobLog entry = logBroker.append(jobId, stream, line);
        if (entry == null) {
            return;
        }
        if (inMemoryLogStore != null) {
            inMemoryLogStore.add(entry);
        }
        if (persistToDb) {
            QuarkusTransaction.requiringNew().run(entry::persist);
        }
    }

    private static void persistJobState(PatchJob job, PhysicalDevice device) {
        QuarkusTransaction.requiringNew()
                .run(
                        () -> {
                            PatchJob managed = PatchJob.findById(job.id);
                            if (managed != null) {
                                managed.status = job.status;
                                managed.currentPhase = job.currentPhase;
                                managed.exitCode = job.exitCode;
                                managed.rebootRequired = job.rebootRequired;
                                managed.errorCode = job.errorCode;
                                managed.errorSummary = job.errorSummary;
                                managed.finishedAt = job.finishedAt;
                                managed.persist();
                            }
                            if (job.rebootRequired != null && device != null) {
                                PhysicalDevice managedDev = PhysicalDevice.findById(device.id);
                                if (managedDev != null) {
                                    managedDev.rebootRequired = job.rebootRequired;
                                    managedDev.persist();
                                }
                            }
                        });
    }

    private void scheduleDelayedRecheck(UUID deviceId) {
        Thread.startVirtualThread(
                () -> {
                    try {
                        Thread.sleep(Duration.ofSeconds(60));
                        inventoryService.scanDevice(deviceId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception ignored) {
                        // Best-effort delayed recheck after SSH connection drop (ADR-4).
                    }
                });
    }

    public boolean cancelJob(UUID jobId) {
        try {
            return workerClient.cancelExecution(jobId).cancelled();
        } catch (Exception e) {
            return false;
        }
    }

    @Scheduled(every = "24h")
    @Transactional
    public void purgeExpiredJobLogs() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        PatchJobLog.delete("ts < ?1", cutoff);
    }

    public static String normalizeMode(String rawMode) {
        String mode =
                rawMode == null || rawMode.isBlank()
                        ? "FULL_UPGRADE"
                        : rawMode.trim().toUpperCase(Locale.ROOT);
        if (!VALID_MODES.contains(mode)) {
            throw new IllegalArgumentException("Unsupported patch mode: " + rawMode);
        }
        return mode;
    }

    public static void validateRequestedPackages(
            String mode, List<String> requestedPackages, Set<String> availableUpdateNames) {
        if (!"SELECTED_PACKAGES".equals(mode)) {
            return;
        }
        if (requestedPackages == null || requestedPackages.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one package must be specified for SELECTED_PACKAGES mode");
        }
        for (String pkg : requestedPackages) {
            if (pkg == null || !VALID_PACKAGE_NAME_PATTERN.matcher(pkg).matches()) {
                throw new IllegalArgumentException("Invalid or forbidden package name: " + pkg);
            }
            if (availableUpdateNames != null && !availableUpdateNames.contains(pkg)) {
                throw new IllegalArgumentException(
                        "Package '" + pkg + "' is not in the device's available updates list");
            }
        }
    }

    private OsProfile resolveProfile(PhysicalDevice device) {
        if (device.osProfile != null) {
            return device.osProfile;
        }
        OsProfile detected = discoveryService.detectAndAssign(device);
        if (detected == null) {
            throw new PkgWorkerException(
                    "UNSUPPORTED_OS", "No matching OS profile found for device", 422);
        }
        return detected;
    }
}
