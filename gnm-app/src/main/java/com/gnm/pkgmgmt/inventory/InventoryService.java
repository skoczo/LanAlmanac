package com.gnm.pkgmgmt.inventory;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.client.CredentialResolver;
import com.gnm.pkgmgmt.client.DeviceLockManager;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.PkgWorkerClient.AuthenticatedCallResult;
import com.gnm.pkgmgmt.client.PkgWorkerException;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventoryResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.NormalizedPackage;
import com.gnm.pkgmgmt.profile.OsDiscoveryService;
import com.gnm.pkgmgmt.profile.OsProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
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
public class InventoryService {

    private final DeviceLockManager lockManager;
    private final PkgWorkerClient workerClient;
    private final OsDiscoveryService discoveryService;
    private final Event<InventoryUpdatedEvent> inventoryUpdatedEvent;

    @Inject
    public InventoryService(
            DeviceLockManager lockManager,
            PkgWorkerClient workerClient,
            OsDiscoveryService discoveryService,
            Event<InventoryUpdatedEvent> inventoryUpdatedEvent) {
        this.lockManager = lockManager;
        this.workerClient = workerClient;
        this.discoveryService = discoveryService;
        this.inventoryUpdatedEvent = inventoryUpdatedEvent;
    }

    public DevicePackageScan scanDevice(UUID deviceId) {
        if (deviceId == null) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }
        return lockManager.withDeviceLock(
                deviceId,
                Duration.ofMinutes(10),
                () -> {
                    ScanContext ctx =
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
                                                DevicePackageScan scan = new DevicePackageScan();
                                                scan.physicalDevice = device;
                                                scan.osProfile = profile;
                                                scan.status = "RUNNING";
                                                scan.startedAt = Instant.now();
                                                scan.persist();
                                                return new ScanContext(device, profile, scan.id);
                                            });

                    try {
                        AuthenticatedCallResult<InventoryResponse> call =
                                workerClient.collectInventory(
                                        ctx.device(),
                                        ctx.profile().environmentVars,
                                        ctx.device().osFacts != null ? ctx.device().osFacts : Map.of(),
                                        ctx.profile().inventorySpec,
                                        ctx.profile().privilegeSpec,
                                        null);

                        return QuarkusTransaction.requiringNew()
                                .call(
                                        () -> {
                                            PhysicalDevice device = PhysicalDevice.findById(deviceId);
                                            DevicePackageScan scan = DevicePackageScan.findById(ctx.scanId());
                                            OsProfile profile = OsProfile.findById(ctx.profile().id);
                                            List<DevicePackage> existing =
                                                    DevicePackage.list("physicalDevice.id", deviceId);
                                            reconcilePackages(
                                                    device,
                                                    profile,
                                                    call.response().packages(),
                                                    scan.id,
                                                    existing,
                                                    true);

                                            scan.credentialId = call.credentialId();
                                            scan.credentialUsername = call.credentialUsername();
                                            scan.usedFallback = call.usedFallback();
                                            scan.status = "SUCCEEDED";
                                            scan.packageCount = call.response().totalParsed();
                                            scan.skippedLines = call.response().skippedLines();
                                            scan.finishedAt = Instant.now();
                                            scan.persist();

                                            if (inventoryUpdatedEvent != null) {
                                                inventoryUpdatedEvent.fireAsync(
                                                        new InventoryUpdatedEvent(deviceId));
                                            }
                                            return scan;
                                        });
                    } catch (PkgWorkerException e) {
                        QuarkusTransaction.requiringNew()
                                .run(
                                        () -> {
                                            DevicePackageScan scan = DevicePackageScan.findById(ctx.scanId());
                                            if (scan != null) {
                                                scan.status = "FAILED";
                                                scan.errorCode = e.errorCode();
                                                scan.errorMessage = e.getMessage();
                                                scan.finishedAt = Instant.now();
                                                scan.persist();
                                            }
                                        });
                        throw e;
                    } catch (Exception e) {
                        QuarkusTransaction.requiringNew()
                                .run(
                                        () -> {
                                            DevicePackageScan scan = DevicePackageScan.findById(ctx.scanId());
                                            if (scan != null) {
                                                scan.status = "FAILED";
                                                scan.errorCode = "INVENTORY_ERROR";
                                                scan.errorMessage = e.getMessage();
                                                scan.finishedAt = Instant.now();
                                                scan.persist();
                                            }
                                        });
                        if (e instanceof RuntimeException re) {
                            throw re;
                        }
                        throw new RuntimeException(e);
                    }
                });
    }

    private record ScanContext(PhysicalDevice device, OsProfile profile, UUID scanId) {}

    /**
     * In-memory execution entry point for deterministic unit testing without a live Panache session.
     */
    public DevicePackageScan executeScanInMemory(
            PhysicalDevice device, List<DevicePackage> mutableStore) {
        return lockManager.withDeviceLock(
                device.id,
                Duration.ofMinutes(5),
                () -> {
                    OsProfile profile = resolveProfile(device);
                    DevicePackageScan scan = new DevicePackageScan();
                    scan.id = UUID.randomUUID();
                    scan.physicalDevice = device;
                    scan.osProfile = profile;
                    scan.startedAt = Instant.now();

                    AuthenticatedCallResult<InventoryResponse> call =
                            workerClient.collectInventory(
                                    device,
                                    profile.environmentVars,
                                    device.osFacts != null ? device.osFacts : Map.of(),
                                    profile.inventorySpec,
                                    profile.privilegeSpec,
                                    null);

                    reconcilePackages(
                            device,
                            profile,
                            call.response().packages(),
                            scan.id,
                            mutableStore,
                            false);

                    scan.credentialId = call.credentialId();
                    scan.credentialUsername = call.credentialUsername();
                    scan.usedFallback = call.usedFallback();
                    scan.status = "SUCCEEDED";
                    scan.packageCount = call.response().totalParsed();
                    scan.skippedLines = call.response().skippedLines();
                    scan.finishedAt = Instant.now();

                    if (inventoryUpdatedEvent != null) {
                        inventoryUpdatedEvent.fireAsync(new InventoryUpdatedEvent(device.id));
                    }
                    return scan;
                });
    }

    public static void reconcilePackages(
            PhysicalDevice device,
            OsProfile profile,
            List<NormalizedPackage> incoming,
            UUID scanId,
            List<DevicePackage> existing,
            boolean persistToDb) {
        String ecosystem = resolveEcosystem(profile, device.osFacts);
        Instant now = Instant.now();

        Map<String, DevicePackage> existingByKey = new HashMap<>();
        for (DevicePackage p : existing) {
            existingByKey.put(packageKey(p.name, p.arch), p);
        }

        Set<String> seenKeys = new HashSet<>();
        if (incoming != null) {
            for (NormalizedPackage np : incoming) {
                if (np == null || np.name() == null || np.version() == null) {
                    continue;
                }
                String arch = np.arch() != null ? np.arch() : "";
                String key = packageKey(np.name(), arch);
                if (!seenKeys.add(key)) {
                    continue;
                }

                String srcName =
                        (np.sourceName() != null && !np.sourceName().isBlank())
                                ? np.sourceName()
                                : np.name();
                String srcVer =
                        (np.sourceVersion() != null && !np.sourceVersion().isBlank())
                                ? np.sourceVersion()
                                : np.version();

                DevicePackage target = existingByKey.get(key);
                if (target == null) {
                    target = new DevicePackage();
                    target.physicalDevice = device;
                    target.name = np.name();
                    target.arch = arch;
                    existing.add(target);
                }
                target.version = np.version();
                target.purl = np.purl();
                target.ecosystem = ecosystem;
                target.sourceName = srcName;
                target.sourceVersion = srcVer;
                target.lastScanId = scanId;
                target.lastSeenAt = now;
                if (persistToDb) {
                    target.persist();
                }
            }
        }

        Iterator<DevicePackage> it = existing.iterator();
        while (it.hasNext()) {
            DevicePackage p = it.next();
            if (!seenKeys.contains(packageKey(p.name, p.arch))) {
                if (persistToDb) {
                    p.delete();
                }
                it.remove();
            }
        }
    }

    public static String resolveEcosystem(OsProfile profile, Map<String, String> osFacts) {
        if (profile == null
                || profile.vulnerabilitySpec == null
                || profile.vulnerabilitySpec.osvEcosystemTemplate() == null
                || profile.vulnerabilitySpec.osvEcosystemTemplate().isBlank()) {
            return "Generic";
        }
        String tpl = profile.vulnerabilitySpec.osvEcosystemTemplate();
        if (osFacts != null) {
            for (Map.Entry<String, String> entry : osFacts.entrySet()) {
                tpl = tpl.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
        }
        return tpl;
    }

    private OsProfile resolveProfile(PhysicalDevice device) {
        if (device.osProfile != null) {
            return device.osProfile;
        }
        OsProfile detected = discoveryService.detectAndAssign(device);
        if (detected == null) {
            throw new PkgWorkerException(
                    "UNSUPPORTED_OS",
                    "No matching OS profile found for device",
                    422);
        }
        return detected;
    }

    private static String packageKey(String name, String arch) {
        return name + "\0" + (arch != null ? arch : "");
    }
}

