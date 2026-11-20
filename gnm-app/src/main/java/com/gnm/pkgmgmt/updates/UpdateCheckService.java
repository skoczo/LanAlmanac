package com.gnm.pkgmgmt.updates;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.client.CredentialResolver;
import com.gnm.pkgmgmt.client.DeviceLockManager;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.PkgWorkerClient.AuthenticatedCallResult;
import com.gnm.pkgmgmt.client.PkgWorkerException;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.AvailableUpdate;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckResponse;
import com.gnm.pkgmgmt.profile.OsDiscoveryService;
import com.gnm.pkgmgmt.profile.OsProfile;
import com.gnm.pkgmgmt.vulnerability.DeviceVulnerability;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class UpdateCheckService {

    private final DeviceLockManager lockManager;
    private final PkgWorkerClient workerClient;
    private final OsDiscoveryService discoveryService;

    @Inject
    public UpdateCheckService(
            DeviceLockManager lockManager,
            PkgWorkerClient workerClient,
            OsDiscoveryService discoveryService) {
        this.lockManager = lockManager;
        this.workerClient = workerClient;
        this.discoveryService = discoveryService;
    }

    public List<DeviceAvailableUpdate> checkDeviceUpdates(UUID deviceId, boolean skipRefresh) {
        if (deviceId == null) {
            throw new IllegalArgumentException("deviceId is required");
        }
        return lockManager.withDeviceLock(
                deviceId,
                Duration.ofMinutes(10),
                () -> {
                    DeviceUpdateContext ctx =
                            QuarkusTransaction.requiringNew()
                                    .call(
                                            () -> {
                                                PhysicalDevice device = PhysicalDevice.findById(deviceId);
                                                if (device == null) {
                                                    throw new IllegalArgumentException(
                                                            "Device not found: " + deviceId);
                                                }
                                                OsProfile profile = resolveProfile(device);
                                                CredentialResolver.initializeForExecution(device);
                                                return new DeviceUpdateContext(device, profile);
                                            });

                    AuthenticatedCallResult<UpdateCheckResponse> call =
                            workerClient.checkUpdates(
                                    ctx.device(),
                                    ctx.profile().environmentVars,
                                    skipRefresh,
                                    ctx.profile().updateCheckSpec,
                                    ctx.profile().privilegeSpec,
                                    ctx.profile().lockSpec);

                    return QuarkusTransaction.requiringNew()
                            .call(
                                    () -> {
                                        PhysicalDevice device = PhysicalDevice.findById(deviceId);
                                        if (device == null) {
                                            throw new IllegalArgumentException(
                                                    "Device not found: " + deviceId);
                                        }
                                        List<DeviceAvailableUpdate> existingUpdates =
                                                DeviceAvailableUpdate.list(
                                                        "physicalDevice.id", deviceId);
                                        List<DeviceVulnerability> vulns =
                                                DeviceVulnerability.list(
                                                        "physicalDevice.id", deviceId);

                                        reconcileUpdates(
                                                device,
                                                call.response().updates(),
                                                existingUpdates,
                                                vulns,
                                                true);
                                        device.persist();
                                        return DeviceAvailableUpdate.list(
                                                "physicalDevice.id = ?1 order by packageName asc",
                                                deviceId);
                                    });
                });
    }

    private record DeviceUpdateContext(PhysicalDevice device, OsProfile profile) {}

    /**
     * In-memory execution entry point for deterministic unit testing.
     */
    public void executeCheckInMemory(
            PhysicalDevice device,
            boolean skipRefresh,
            List<DeviceAvailableUpdate> updateStore,
            List<DeviceVulnerability> vulnStore) {
        lockManager.withDeviceLock(
                device.id,
                Duration.ofMinutes(5),
                () -> {
                    OsProfile profile = resolveProfile(device);
                    AuthenticatedCallResult<UpdateCheckResponse> call =
                            workerClient.checkUpdates(
                                    device,
                                    profile.environmentVars,
                                    skipRefresh,
                                    profile.updateCheckSpec,
                                    profile.privilegeSpec,
                                    profile.lockSpec);

                    reconcileUpdates(
                            device,
                            call.response().updates(),
                            updateStore,
                            vulnStore,
                            false);
                });
    }

    public static void reconcileUpdates(
            PhysicalDevice device,
            List<AvailableUpdate> incoming,
            List<DeviceAvailableUpdate> existingUpdates,
            List<DeviceVulnerability> vulnerabilities,
            boolean persistToDb) {
        Instant now = Instant.now();
        Map<String, DeviceAvailableUpdate> existingByKey = new HashMap<>();
        for (DeviceAvailableUpdate u : existingUpdates) {
            existingByKey.put(updateKey(u.packageName, u.arch), u);
        }

        Set<String> seenKeys = new HashSet<>();
        Set<String> updatablePackageNames = new HashSet<>();

        if (incoming != null) {
            for (AvailableUpdate au : incoming) {
                if (au == null
                        || au.packageName() == null
                        || au.candidateVersion() == null) {
                    continue;
                }
                String arch = au.arch() != null ? au.arch() : "";
                String key = updateKey(au.packageName(), arch);
                if (!seenKeys.add(key)) {
                    continue;
                }
                updatablePackageNames.add(au.packageName());

                DeviceAvailableUpdate target = existingByKey.get(key);
                if (target == null) {
                    target = new DeviceAvailableUpdate();
                    if (!persistToDb) {
                        target.id = UUID.randomUUID();
                    }
                    target.physicalDevice = device;
                    target.packageName = au.packageName();
                    target.arch = arch;
                    existingUpdates.add(target);
                }
                target.currentVersion = au.currentVersion();
                target.candidateVersion = au.candidateVersion();
                target.isSecurityUpdate = au.isSecurityUpdate();
                target.checkedAt = now;
                if (persistToDb) {
                    target.persist();
                }
            }
        }

        Iterator<DeviceAvailableUpdate> it = existingUpdates.iterator();
        while (it.hasNext()) {
            DeviceAvailableUpdate u = it.next();
            if (!seenKeys.contains(updateKey(u.packageName, u.arch))) {
                if (persistToDb) {
                    u.delete();
                }
                it.remove();
            }
        }

        if (vulnerabilities != null) {
            for (DeviceVulnerability v : vulnerabilities) {
                if (v == null) {
                    continue;
                }
                boolean fixNow =
                        "OPEN".equals(v.status) && updatablePackageNames.contains(v.packageName);
                if (v.fixAvailableNow != fixNow) {
                    v.fixAvailableNow = fixNow;
                    if (persistToDb) {
                        v.persist();
                    }
                }
            }
        }

        device.updatesLastCheckedAt = now;
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

    private static String updateKey(String pkgName, String arch) {
        return pkgName + "\0" + (arch != null ? arch : "");
    }
}

