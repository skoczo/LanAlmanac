package com.gnm.resource;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.PkgWorkerClient.AuthenticatedCallResult;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.HealthResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventoryResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.ProbeResponse;
import com.gnm.pkgmgmt.dto.OsProfileDtos;
import com.gnm.pkgmgmt.dto.OsProfileDtos.CloneProfileRequest;
import com.gnm.pkgmgmt.dto.OsProfileDtos.OsProfileDryRunRequest;
import com.gnm.pkgmgmt.dto.OsProfileDtos.OsProfileDryRunResponse;
import com.gnm.pkgmgmt.dto.OsProfileDtos.OsProfileDto;
import com.gnm.pkgmgmt.dto.OsProfileDtos.OsProfileExportDto;
import com.gnm.pkgmgmt.dto.OsProfileDtos.OsProfileUpsertRequest;
import com.gnm.pkgmgmt.dto.OsProfileDtos.WorkerHealthStatusDto;
import com.gnm.pkgmgmt.profile.OsProfile;
import com.gnm.pkgmgmt.profile.OsProfileMatcher;
import com.gnm.pkgmgmt.profile.ProfileValidator;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@Path("/api/os-profiles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"gnm-admin", "gnm-operator", "gnm-viewer"})
public class OsProfileResource {

    private static final Logger log = Logger.getLogger(OsProfileResource.class);
    private static final String KEY_ERROR = "error";
    private static final String KEY_ERROR_CODE = "errorCode";
    private static final int DRY_RUN_PREVIEW_LIMIT = 50;

    private final ProfileValidator profileValidator;
    private final OsProfileMatcher profileMatcher;
    private final PkgWorkerClient workerClient;
    private final SecurityIdentity securityIdentity;

    @Inject
    public OsProfileResource(
            ProfileValidator profileValidator,
            OsProfileMatcher profileMatcher,
            PkgWorkerClient workerClient,
            SecurityIdentity securityIdentity) {
        this.profileValidator = profileValidator;
        this.profileMatcher = profileMatcher;
        this.workerClient = workerClient;
        this.securityIdentity = securityIdentity;
    }

    @GET
    public List<OsProfileDto> listAll() {
        List<OsProfile> profiles = OsProfile.list("order by priority asc, slug asc");
        return profiles.stream().map(OsProfileDto::fromEntity).toList();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") UUID id) {
        OsProfile profile = OsProfile.findById(id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(OsProfileDto.fromEntity(profile)).build();
    }

    @GET
    @Path("/{id}/export")
    public Response exportProfile(@PathParam("id") UUID id) {
        OsProfile profile = OsProfile.findById(id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(OsProfileExportDto.fromEntity(profile)).build();
    }

    @POST
    @RolesAllowed("gnm-admin")
    @Transactional
    public Response createProfile(OsProfileUpsertRequest req) {
        if (req == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, "Request body is required"))
                    .build();
        }
        OsProfile profile = new OsProfile();
        applyRequest(profile, req);

        try {
            profileValidator.validate(profile);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, e.getMessage()))
                    .build();
        }

        if (OsProfile.find("slug", profile.slug).firstResult() != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(KEY_ERROR, "OS profile with slug '" + profile.slug + "' already exists"))
                    .build();
        }

        profile.isSystem = false;
        profile.createdAt = Instant.now();
        profile.updatedAt = Instant.now();
        profile.persist();

        log.infof("AUDIT: OS profile '%s' (%s) created by %s", profile.slug, profile.id, currentActor());
        return Response.status(Response.Status.CREATED)
                .entity(OsProfileDto.fromEntity(profile))
                .build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("gnm-admin")
    @Transactional
    public Response updateProfile(@PathParam("id") UUID id, OsProfileUpsertRequest req) {
        OsProfile profile = OsProfile.findById(id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (profile.isSystem) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(
                            Map.of(
                                    KEY_ERROR,
                                    "Built-in system profiles are read-only. Clone the profile to customize it."))
                    .build();
        }
        if (req == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, "Request body is required"))
                    .build();
        }

        applyRequest(profile, req);
        try {
            profileValidator.validate(profile);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, e.getMessage()))
                    .build();
        }

        OsProfile existingBySlug = OsProfile.find("slug", profile.slug).firstResult();
        if (existingBySlug != null && !existingBySlug.id.equals(profile.id)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(KEY_ERROR, "OS profile with slug '" + profile.slug + "' already exists"))
                    .build();
        }

        profile.updatedAt = Instant.now();
        profile.persist();

        log.infof("AUDIT: OS profile '%s' (%s) updated by %s", profile.slug, profile.id, currentActor());
        return Response.ok(OsProfileDto.fromEntity(profile)).build();
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("gnm-admin")
    @Transactional
    public Response deleteProfile(@PathParam("id") UUID id) {
        OsProfile profile = OsProfile.findById(id);
        if (profile == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (profile.isSystem) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(KEY_ERROR, "Built-in system profiles cannot be deleted"))
                    .build();
        }
        String slug = profile.slug;
        profile.delete();
        log.infof("AUDIT: OS profile '%s' (%s) deleted by %s", slug, id, currentActor());
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/clone")
    @RolesAllowed("gnm-admin")
    @Transactional
    public Response cloneProfile(@PathParam("id") UUID id, CloneProfileRequest req) {
        OsProfile source = OsProfile.findById(id);
        if (source == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String newSlug =
                (req != null && req.newSlug() != null && !req.newSlug().isBlank())
                        ? req.newSlug().trim()
                        : source.slug + "-custom";
        String newName =
                (req != null && req.newDisplayName() != null && !req.newDisplayName().isBlank())
                        ? req.newDisplayName().trim()
                        : source.displayName + " (Custom)";

        OsProfile clone = new OsProfile();
        clone.slug = newSlug;
        clone.displayName = newName;
        clone.transportType = source.transportType;
        clone.priority = source.priority;
        clone.enabled = source.enabled;
        clone.isSystem = false;
        clone.detectionRules = source.detectionRules;
        clone.environmentVars = source.environmentVars;
        clone.inventorySpec = source.inventorySpec;
        clone.updateCheckSpec = source.updateCheckSpec;
        clone.patchExecSpec = source.patchExecSpec;
        clone.vulnerabilitySpec = source.vulnerabilitySpec;
        clone.privilegeSpec = source.privilegeSpec;
        clone.lockSpec = source.lockSpec;

        try {
            profileValidator.validate(clone);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, e.getMessage()))
                    .build();
        }

        if (OsProfile.find("slug", clone.slug).firstResult() != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(KEY_ERROR, "OS profile with slug '" + clone.slug + "' already exists"))
                    .build();
        }

        clone.createdAt = Instant.now();
        clone.updatedAt = Instant.now();
        clone.persist();

        log.infof(
                "AUDIT: OS profile '%s' cloned to '%s' (%s) by %s",
                source.slug, clone.slug, clone.id, currentActor());
        return Response.status(Response.Status.CREATED)
                .entity(OsProfileDto.fromEntity(clone))
                .build();
    }

    @POST
    @Path("/import")
    @RolesAllowed("gnm-admin")
    @Transactional
    public Response importProfile(OsProfileExportDto imported) {
        if (imported == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, "Import payload is required"))
                    .build();
        }
        if (imported.schemaVersion() != OsProfileDtos.CURRENT_SCHEMA_VERSION) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            Map.of(
                                    KEY_ERROR,
                                    "Unsupported schemaVersion: "
                                            + imported.schemaVersion()
                                            + " (expected "
                                            + OsProfileDtos.CURRENT_SCHEMA_VERSION
                                            + ")"))
                    .build();
        }

        return createProfile(
                new OsProfileUpsertRequest(
                        imported.slug(),
                        imported.displayName(),
                        imported.transportType(),
                        imported.priority(),
                        true,
                        imported.detectionRules(),
                        imported.environmentVars(),
                        imported.inventorySpec(),
                        imported.updateCheckSpec(),
                        imported.patchExecSpec(),
                        imported.vulnerabilitySpec(),
                        imported.privilegeSpec(),
                        imported.lockSpec()));
    }

    @GET
    @Path("/worker-health")
    public Response getWorkerHealth() {
        try {
            HealthResponse health = workerClient.getHealth();
            return Response.ok(new WorkerHealthStatusDto(true, health, null)).build();
        } catch (Exception e) {
            return Response.ok(new WorkerHealthStatusDto(false, null, e.getMessage())).build();
        }
    }

    @POST
    @Path("/test")
    @RolesAllowed("gnm-admin")
    public Response dryRunProfile(OsProfileDryRunRequest req) {
        if (req == null || req.deviceId() == null || req.profile() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(KEY_ERROR, "deviceId and profile are required"))
                    .build();
        }
        PhysicalDevice device = PhysicalDevice.findById(req.deviceId());
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of(KEY_ERROR, "Target device not found"))
                    .build();
        }
        return Response.ok(executeDryRun(device, req.profile())).build();
    }

    public OsProfileDryRunResponse executeDryRun(
            PhysicalDevice device, OsProfileUpsertRequest profileReq) {
        OsProfile candidate = new OsProfile();
        applyRequest(candidate, profileReq);
        profileValidator.validate(candidate);

        String verifyCmd =
                candidate.detectionRules != null ? candidate.detectionRules.verifyCommand() : null;
        Map<String, String> verifyMap =
                (verifyCmd != null && !verifyCmd.isBlank())
                        ? Map.of(candidate.slug, verifyCmd)
                        : Map.of();

        AuthenticatedCallResult<ProbeResponse> probeCall =
                workerClient.probeDevice(device, verifyMap);
        Map<String, String> osFacts =
                probeCall.response().osFacts() != null ? probeCall.response().osFacts() : Map.of();
        boolean matched =
                profileMatcher.findMatch(
                                List.of(candidate), osFacts, probeCall.response().verifyResults())
                        .isPresent();

        AuthenticatedCallResult<InventoryResponse> invCall =
                workerClient.collectInventory(
                        device,
                        candidate.environmentVars,
                        osFacts,
                        candidate.inventorySpec,
                        candidate.privilegeSpec,
                        DRY_RUN_PREVIEW_LIMIT);
        InventoryResponse inv = invCall.response();
        return new OsProfileDryRunResponse(
                matched,
                osFacts,
                inv.packages() != null ? inv.packages() : List.of(),
                inv.totalParsed(),
                inv.skippedLines(),
                inv.sampleSkippedLines() != null ? inv.sampleSkippedLines() : List.of(),
                inv.durationMs(),
                invCall.credentialUsername(),
                invCall.usedFallback());
    }

    private static void applyRequest(OsProfile target, OsProfileUpsertRequest req) {
        target.slug = req.slug() != null ? req.slug().trim() : null;
        target.displayName = req.displayName() != null ? req.displayName().trim() : null;
        target.transportType = req.transportType() != null ? req.transportType().trim() : "SSH";
        target.priority = req.priority() != null ? req.priority() : 100;
        target.enabled = req.enabled() != null ? req.enabled() : true;
        target.detectionRules = req.detectionRules();
        target.environmentVars =
                req.environmentVars() != null
                        ? req.environmentVars()
                        : Map.of("LC_ALL", "C", "LANG", "C");
        target.inventorySpec = req.inventorySpec();
        target.updateCheckSpec = req.updateCheckSpec();
        target.patchExecSpec = req.patchExecSpec();
        target.vulnerabilitySpec = req.vulnerabilitySpec();
        target.privilegeSpec = req.privilegeSpec();
        target.lockSpec = req.lockSpec();
    }

    private String currentActor() {
        if (securityIdentity != null
                && !securityIdentity.isAnonymous()
                && securityIdentity.getPrincipal() != null) {
            return securityIdentity.getPrincipal().getName();
        }
        return "unknown";
    }
}

