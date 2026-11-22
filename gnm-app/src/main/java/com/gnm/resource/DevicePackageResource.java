package com.gnm.resource;

import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.client.CredentialResolver;
import com.gnm.pkgmgmt.client.CredentialResolver.ResolvedCandidate;
import com.gnm.pkgmgmt.client.PkgWorkerException;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.DeviceAvailableUpdateDto;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.DeviceOsProfileAssignmentRequest;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.DeviceOsProfileStatusDto;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.DevicePackageDto;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.DevicePackageScanDto;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.DevicePackageScheduleDto;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.DevicePackageScheduleUpdateRequest;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.DeviceVulnerabilityDto;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.PatchJobCreateRequest;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.PatchJobDto;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.SudoersSnippetDto;
import com.gnm.pkgmgmt.inventory.CycloneDxExportService;
import com.gnm.pkgmgmt.inventory.DevicePackage;
import com.gnm.pkgmgmt.inventory.DevicePackageScan;
import com.gnm.pkgmgmt.inventory.InventoryService;
import com.gnm.pkgmgmt.patching.PatchJob;
import com.gnm.pkgmgmt.patching.PatchJobService;
import com.gnm.pkgmgmt.profile.OsDiscoveryService;
import com.gnm.pkgmgmt.profile.OsProfile;
import com.gnm.pkgmgmt.schedule.CronScheduleCalculator;
import com.gnm.pkgmgmt.schedule.DevicePackageSchedule;
import com.gnm.pkgmgmt.updates.DeviceAvailableUpdate;
import com.gnm.pkgmgmt.updates.SudoersSnippetGenerator;
import com.gnm.pkgmgmt.updates.UpdateCheckService;
import com.gnm.pkgmgmt.vulnerability.DeviceVulnerability;
import com.gnm.pkgmgmt.vulnerability.VulnerabilityAssessmentService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Path("/api/devices/{id}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"gnm-admin", "gnm-operator", "gnm-viewer"})
public class DevicePackageResource {

    private static final String KEY_ERROR = "error";
    private static final String KEY_ERROR_CODE = "errorCode";

    private final InventoryService inventoryService;
    private final CycloneDxExportService cycloneDxExportService;
    private final OsDiscoveryService osDiscoveryService;
    private final VulnerabilityAssessmentService vulnerabilityService;
    private final UpdateCheckService updateCheckService;
    private final SudoersSnippetGenerator sudoersSnippetGenerator;
    private final CredentialResolver credentialResolver;
    private final CronScheduleCalculator cronCalculator;
    private final SecurityIdentity securityIdentity;
    private final PatchJobService patchJobService;

    @Inject
    public DevicePackageResource(
            InventoryService inventoryService,
            CycloneDxExportService cycloneDxExportService,
            OsDiscoveryService osDiscoveryService,
            VulnerabilityAssessmentService vulnerabilityService,
            UpdateCheckService updateCheckService,
            SudoersSnippetGenerator sudoersSnippetGenerator,
            CredentialResolver credentialResolver,
            CronScheduleCalculator cronCalculator,
            SecurityIdentity securityIdentity,
            PatchJobService patchJobService) {
        this.inventoryService = inventoryService;
        this.cycloneDxExportService = cycloneDxExportService;
        this.osDiscoveryService = osDiscoveryService;
        this.vulnerabilityService = vulnerabilityService;
        this.updateCheckService = updateCheckService;
        this.sudoersSnippetGenerator = sudoersSnippetGenerator;
        this.credentialResolver = credentialResolver;
        this.cronCalculator = cronCalculator;
        this.securityIdentity = securityIdentity;
        this.patchJobService = patchJobService;
    }

    @GET
    @Path("/os-profile")
    @Transactional
    public Response getOsProfileStatus(@PathParam("id") UUID deviceId) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(buildOsProfileStatus(device)).build();
    }

    @PUT
    @Path("/os-profile")
    @RolesAllowed({"gnm-admin", "gnm-operator"})
    @Transactional
    public Response updateOsProfileAssignment(
            @PathParam("id") UUID deviceId, DeviceOsProfileAssignmentRequest req) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (req == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, "Request body is required"))
                    .build();
        }

        if (req.osProfileId() != null) {
            OsProfile profile = OsProfile.findById(req.osProfileId());
            if (profile == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of(KEY_ERROR, "OS profile not found"))
                        .build();
            }
            device.osProfile = profile;
            device.osProfilePinned = req.pinned();
        } else {
            device.osProfilePinned = false;
        }
        device.persist();
        return Response.ok(buildOsProfileStatus(device)).build();
    }

    @POST
    @Path("/os-profile/detect")
    @RolesAllowed({"gnm-admin", "gnm-operator"})
    @Transactional
    public Response detectOsProfile(@PathParam("id") UUID deviceId) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        try {
            device.osProfilePinned = false;
            osDiscoveryService.detectAndAssign(device);
            device.persist();
            return Response.ok(buildOsProfileStatus(device)).build();
        } catch (PkgWorkerException e) {
            return Response.status(e.httpStatus())
                    .entity(Map.of(KEY_ERROR_CODE, e.errorCode(), KEY_ERROR, e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/packages")
    @Transactional
    public Response getDevicePackages(
            @PathParam("id") UUID deviceId,
            @QueryParam("q") String query,
            @QueryParam("vulnerableOnly") boolean vulnerableOnly,
            @QueryParam("updatesOnly") boolean updatesOnly) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        List<DevicePackage> packages = DevicePackage.list("physicalDevice.id", deviceId);
        List<DeviceVulnerability> openVulns =
                DeviceVulnerability.list(
                        "physicalDevice.id = ?1 and status = 'OPEN'", deviceId);
        List<DeviceAvailableUpdate> updates =
                DeviceAvailableUpdate.list("physicalDevice.id", deviceId);

        Map<String, Integer> vulnCountByPkg = new HashMap<>();
        for (DeviceVulnerability v : openVulns) {
            vulnCountByPkg.merge(v.packageName, 1, Integer::sum);
        }

        Map<String, DeviceAvailableUpdate> updatesByPkg = new HashMap<>();
        for (DeviceAvailableUpdate u : updates) {
            if (u.packageName != null) {
                updatesByPkg.put(u.packageName, u);
            }
        }

        String qLower = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        List<DevicePackageDto> dtos =
                packages.stream()
                        .filter(
                                p ->
                                        qLower.isEmpty()
                                                || p.name.toLowerCase(Locale.ROOT).contains(qLower)
                                                || p.version.toLowerCase(Locale.ROOT).contains(qLower)
                                                || p.sourceName
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains(qLower))
                        .map(
                                p -> {
                                    DeviceAvailableUpdate u = updatesByPkg.get(p.name);
                                    String candidate = u != null ? u.candidateVersion : null;
                                    boolean sec = u != null && u.isSecurityUpdate;
                                    return DevicePackageDto.fromEntity(
                                            p,
                                            vulnCountByPkg.getOrDefault(p.name, 0),
                                            candidate,
                                            sec);
                                })
                        .filter(dto -> !vulnerableOnly || dto.openVulnerabilityCount() > 0)
                        .filter(dto -> !updatesOnly || dto.candidateVersion() != null)
                        .sorted(Comparator.comparing(DevicePackageDto::name))
                        .toList();

        return Response.ok(dtos).build();
    }

    @POST
    @Path("/packages/scan")
    @RolesAllowed({"gnm-admin", "gnm-operator"})
    public Response triggerInventoryScan(@PathParam("id") UUID deviceId) {
        try {
            DevicePackageScan scan = inventoryService.scanDevice(deviceId);
            return Response.ok(DevicePackageScanDto.fromEntity(scan)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of(KEY_ERROR, e.getMessage()))
                    .build();
        } catch (PkgWorkerException e) {
            return Response.status(e.httpStatus())
                    .entity(Map.of(KEY_ERROR_CODE, e.errorCode(), KEY_ERROR, e.getMessage()))
                    .build();
        } catch (Exception e) {
            return handleGenericException(e, "Inventory scan failed");
        }
    }

    @GET
    @Path("/sbom")
    @Transactional
    public Response exportCycloneDxSbom(@PathParam("id") UUID deviceId) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        List<DevicePackage> packages =
                DevicePackage.list("physicalDevice.id = ?1 order by name asc", deviceId);
        Map<String, Object> bom = cycloneDxExportService.buildCycloneDxBom(device, packages);
        return Response.ok(bom)
                .header(
                        "Content-Disposition",
                        "attachment; filename=\"sbom-" + deviceId + ".cdx.json\"")
                .build();
    }

    @GET
    @Path("/vulnerabilities")
    @Transactional
    public Response getDeviceVulnerabilities(
            @PathParam("id") UUID deviceId,
            @QueryParam("status") String statusFilter,
            @QueryParam("severity") String severityFilter,
            @QueryParam("fixAvailable") Boolean fixAvailableFilter) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        StringBuilder query = new StringBuilder("physicalDevice.id = :deviceId");
        Map<String, Object> params = new HashMap<>();
        params.put("deviceId", deviceId);

        if (statusFilter != null && !statusFilter.isBlank()) {
            query.append(" and upper(status) = :status");
            params.put("status", statusFilter.trim().toUpperCase(Locale.ROOT));
        }

        if (severityFilter != null && !severityFilter.isBlank()) {
            query.append(" and upper(advisory.severity) = :severity");
            params.put("severity", severityFilter.trim().toUpperCase(Locale.ROOT));
        }

        if (fixAvailableFilter != null) {
            query.append(" and fixAvailableNow = :fixAvailable");
            params.put("fixAvailable", fixAvailableFilter);
        }

        List<DeviceVulnerability> vulns =
                DeviceVulnerability.find(query.toString(), params).list();
        List<DeviceVulnerabilityDto> dtos =
                vulns.stream()
                        .map(DeviceVulnerabilityDto::fromEntity)
                        .toList();
        return Response.ok(dtos).build();
    }

    @POST
    @Path("/vulnerabilities/scan")
    @RolesAllowed({"gnm-admin", "gnm-operator"})
    public Response triggerVulnerabilityScan(@PathParam("id") UUID deviceId) {
        try {
            List<DeviceVulnerability> vulns = vulnerabilityService.assessDevice(deviceId);
            return Response.ok(vulns.stream().map(DeviceVulnerabilityDto::fromEntity).toList())
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of(KEY_ERROR, e.getMessage()))
                    .build();
        } catch (Exception e) {
            return handleGenericException(e, "Vulnerability scan failed");
        }
    }

    @GET
    @Path("/updates")
    @Transactional
    public Response getDeviceAvailableUpdates(@PathParam("id") UUID deviceId) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        List<DeviceAvailableUpdate> updates =
                DeviceAvailableUpdate.list(
                        "physicalDevice.id = ?1 order by packageName asc", deviceId);
        List<DeviceVulnerability> openVulns =
                DeviceVulnerability.list(
                        "physicalDevice.id = ?1 and status = 'OPEN'", deviceId);

        return Response.ok(mapUpdatesWithAdvisories(updates, openVulns)).build();
    }

    @POST
    @Path("/updates/check")
    @RolesAllowed({"gnm-admin", "gnm-operator"})
    public Response triggerUpdateCheck(
            @PathParam("id") UUID deviceId,
            @QueryParam("skipRefresh") boolean skipRefresh) {
        try {
            List<DeviceAvailableUpdate> updates =
                    updateCheckService.checkDeviceUpdates(deviceId, skipRefresh);
            List<DeviceVulnerability> openVulns =
                    DeviceVulnerability.list(
                            "physicalDevice.id = ?1 and status = 'OPEN'", deviceId);
            return Response.ok(mapUpdatesWithAdvisories(updates, openVulns)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of(KEY_ERROR, e.getMessage()))
                    .build();
        } catch (PkgWorkerException e) {
            return Response.status(e.httpStatus())
                    .entity(Map.of(KEY_ERROR_CODE, e.errorCode(), KEY_ERROR, e.getMessage()))
                    .build();
        } catch (Exception e) {
            return handleGenericException(e, "Inventory scan failed");
        }
    }

    @GET
    @Path("/sudoers")
    @Transactional
    public Response getSudoersSnippet(@PathParam("id") UUID deviceId) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (device.osProfile == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, "Detect or assign an OS profile first"))
                    .build();
        }
        List<ResolvedCandidate> candidates = credentialResolver.resolveCandidates(device);
        String username =
                !candidates.isEmpty()
                                && candidates.get(0).credential().username != null
                                && !candidates.get(0).credential().username.isBlank()
                        ? candidates.get(0).credential().username.trim()
                        : "lanalmanac";
        try {
            String snippet = sudoersSnippetGenerator.generate(username, device.osProfile);
            return Response.ok(
                            new SudoersSnippetDto(
                                    username,
                                    device.osProfile.slug,
                                    device.osProfile.displayName,
                                    snippet))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/patch-jobs")
    @RolesAllowed({"gnm-admin", "gnm-operator"})
    public Response startPatchJob(
            @PathParam("id") UUID deviceId, PatchJobCreateRequest req) {
        try {
            String mode = req != null ? req.mode() : "FULL_UPGRADE";
            List<String> packages = req != null ? req.packages() : List.of();
            PatchJob job =
                    patchJobService.startAsyncPatchJob(
                            deviceId, mode, packages, currentActor());
            return Response.status(Response.Status.ACCEPTED)
                    .entity(PatchJobDto.fromEntity(job))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, e.getMessage()))
                    .build();
        } catch (PkgWorkerException e) {
            return Response.status(e.httpStatus())
                    .entity(Map.of(KEY_ERROR_CODE, e.errorCode(), KEY_ERROR, e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/patch-jobs")
    @Transactional
    public List<PatchJobDto> listDevicePatchJobs(@PathParam("id") UUID deviceId) {
        List<PatchJob> jobs =
                PatchJob.list(
                        "physicalDevice.id = ?1 order by createdAt desc", deviceId);
        return jobs.stream().map(PatchJobDto::fromEntity).toList();
    }

    private String currentActor() {
        if (securityIdentity != null
                && securityIdentity.getPrincipal() != null
                && securityIdentity.getPrincipal().getName() != null
                && !securityIdentity.getPrincipal().getName().isBlank()) {
            return securityIdentity.getPrincipal().getName();
        }
        return "system";
    }

    @GET
    @Path("/package-schedule")
    @Transactional
    public Response getPackageSchedule(@PathParam("id") UUID deviceId) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        DevicePackageSchedule schedule = DevicePackageSchedule.findById(deviceId);
        if (schedule == null) {
            schedule = new DevicePackageSchedule();
            schedule.deviceId = deviceId;
            schedule.device = device;
        }
        List<java.time.Instant> preview =
                cronCalculator.previewNextExecutions(
                        schedule.inventoryCron,
                        schedule.timezone,
                        java.time.Instant.now(),
                        3);
        return Response.ok(DevicePackageScheduleDto.fromEntity(schedule, preview)).build();
    }

    @PUT
    @Path("/package-schedule")
    @RolesAllowed({"gnm-admin", "gnm-operator"})
    @Transactional
    public Response updatePackageSchedule(
            @PathParam("id") UUID deviceId, DevicePackageScheduleUpdateRequest req) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (req == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, "Request body is required"))
                    .build();
        }
        try {
            String tz =
                    req.timezone() != null && !req.timezone().isBlank()
                            ? req.timezone().trim()
                            : "UTC";
            cronCalculator.parseTimezone(tz);

            String invCron =
                    req.inventoryCron() != null && !req.inventoryCron().isBlank()
                            ? req.inventoryCron().trim()
                            : "0 3 * * *";
            String updCron =
                    req.updateCheckCron() != null && !req.updateCheckCron().isBlank()
                            ? req.updateCheckCron().trim()
                            : "0 4 * * *";
            String patCron =
                    req.autoPatchCron() != null && !req.autoPatchCron().isBlank()
                            ? req.autoPatchCron().trim()
                            : "0 5 * * 0";

            java.time.Instant now = java.time.Instant.now();
            java.time.Instant nextInv = cronCalculator.nextExecution(invCron, tz, now);
            java.time.Instant nextUpd = cronCalculator.nextExecution(updCron, tz, now);
            java.time.Instant nextPat = cronCalculator.nextExecution(patCron, tz, now);

            DevicePackageSchedule schedule = DevicePackageSchedule.findById(deviceId);
            if (schedule == null) {
                schedule = new DevicePackageSchedule();
                schedule.deviceId = deviceId;
                schedule.device = device;
            }
            schedule.timezone = tz;
            schedule.inventoryEnabled = req.inventoryEnabled();
            schedule.inventoryCron = invCron;
            schedule.nextInventoryRunAt = req.inventoryEnabled() ? nextInv : null;

            schedule.updateCheckEnabled = req.updateCheckEnabled();
            schedule.updateCheckCron = updCron;
            schedule.nextUpdateCheckRunAt = req.updateCheckEnabled() ? nextUpd : null;

            schedule.autoPatchEnabled = req.autoPatchEnabled();
            schedule.autoPatchMode =
                    "FULL_UPGRADE".equalsIgnoreCase(req.autoPatchMode())
                            ? "FULL_UPGRADE"
                            : "SECURITY_ONLY";
            schedule.autoPatchCron = patCron;
            schedule.nextAutoPatchRunAt = req.autoPatchEnabled() ? nextPat : null;

            schedule.updatedBy =
                    securityIdentity != null && securityIdentity.getPrincipal() != null
                            ? securityIdentity.getPrincipal().getName()
                            : "operator";
            schedule.updatedAt = now;
            schedule.persist();

            List<java.time.Instant> preview =
                    cronCalculator.previewNextExecutions(invCron, tz, now, 3);
            return Response.ok(DevicePackageScheduleDto.fromEntity(schedule, preview)).build();
        } catch (BadRequestException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, e.getMessage()))
                    .build();
        }
    }

    private static List<DeviceAvailableUpdateDto> mapUpdatesWithAdvisories(
            List<DeviceAvailableUpdate> updates, List<DeviceVulnerability> openVulns) {
        Map<String, List<String>> advisoriesByPkg = new HashMap<>();
        for (DeviceVulnerability v : openVulns) {
            if (v.advisory != null) {
                advisoriesByPkg
                        .computeIfAbsent(v.packageName, k -> new ArrayList<>())
                        .add(v.advisory.id);
            }
        }
        return updates.stream()
                .map(
                        u ->
                                DeviceAvailableUpdateDto.fromEntity(
                                        u, advisoriesByPkg.getOrDefault(u.packageName, List.of())))
                .toList();
    }

    private DeviceOsProfileStatusDto buildOsProfileStatus(PhysicalDevice device) {
        List<ResolvedCandidate> candidates = credentialResolver.resolveCandidates(device);
        String credUsername =
                candidates.isEmpty() ? null : candidates.get(0).credential().username;
        String credSource =
                candidates.isEmpty() ? "NONE" : candidates.get(0).resolutionSource();
        boolean trusted =
                CredentialResolver.findSshService(device)
                        .map((NetworkService s) -> Boolean.TRUE.equals(s.sshHostKeyTrusted))
                        .orElse(false);

        OsProfile p = device.osProfile;
        return new DeviceOsProfileStatusDto(
                p != null ? p.id : null,
                p != null ? p.slug : null,
                p != null ? p.displayName : null,
                device.osProfilePinned,
                device.osFacts != null ? device.osFacts : Map.of(),
                device.osDetectedAt,
                credUsername,
                credSource,
                trusted);
    }

    private static Response handleGenericException(Exception e, String fallbackMessage) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
            if (root instanceof PkgWorkerException pwe) {
                return Response.status(pwe.httpStatus())
                        .entity(Map.of(KEY_ERROR_CODE, pwe.errorCode(), KEY_ERROR, pwe.getMessage()))
                        .build();
            }
        }
        String msg = root.getMessage() != null && !root.getMessage().isBlank() ? root.getMessage() : e.getMessage();
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of(KEY_ERROR, msg != null && !msg.isBlank() ? msg : fallbackMessage))
                .build();
    }
}

