package com.gnm.pkgmgmt.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.HealthResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventorySpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.LockSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.NormalizedPackage;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchExecSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PrivilegeSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckSpec;
import com.gnm.pkgmgmt.profile.OsProfile;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionRules;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.VulnerabilitySpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OsProfileDtos {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private OsProfileDtos() {}

    public record OsProfileDto(
            UUID id,
            String slug,
            String displayName,
            String transportType,
            int priority,
            boolean enabled,
            boolean isSystem,
            DetectionRules detectionRules,
            Map<String, String> environmentVars,
            InventorySpec inventorySpec,
            UpdateCheckSpec updateCheckSpec,
            PatchExecSpec patchExecSpec,
            VulnerabilitySpec vulnerabilitySpec,
            PrivilegeSpec privilegeSpec,
            LockSpec lockSpec,
            Instant createdAt,
            Instant updatedAt) {

        public static OsProfileDto fromEntity(OsProfile p) {
            return new OsProfileDto(
                    p.id,
                    p.slug,
                    p.displayName,
                    p.transportType,
                    p.priority,
                    p.enabled,
                    p.isSystem,
                    p.detectionRules,
                    p.environmentVars,
                    p.inventorySpec,
                    p.updateCheckSpec,
                    p.patchExecSpec,
                    p.vulnerabilitySpec,
                    p.privilegeSpec,
                    p.lockSpec,
                    p.createdAt,
                    p.updatedAt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OsProfileUpsertRequest(
            String slug,
            String displayName,
            String transportType,
            Integer priority,
            Boolean enabled,
            DetectionRules detectionRules,
            Map<String, String> environmentVars,
            InventorySpec inventorySpec,
            UpdateCheckSpec updateCheckSpec,
            PatchExecSpec patchExecSpec,
            VulnerabilitySpec vulnerabilitySpec,
            PrivilegeSpec privilegeSpec,
            LockSpec lockSpec) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OsProfileExportDto(
            int schemaVersion,
            String slug,
            String displayName,
            String transportType,
            int priority,
            DetectionRules detectionRules,
            Map<String, String> environmentVars,
            InventorySpec inventorySpec,
            UpdateCheckSpec updateCheckSpec,
            PatchExecSpec patchExecSpec,
            VulnerabilitySpec vulnerabilitySpec,
            PrivilegeSpec privilegeSpec,
            LockSpec lockSpec) {

        public static OsProfileExportDto fromEntity(OsProfile p) {
            return new OsProfileExportDto(
                    CURRENT_SCHEMA_VERSION,
                    p.slug,
                    p.displayName,
                    p.transportType,
                    p.priority,
                    p.detectionRules,
                    p.environmentVars,
                    p.inventorySpec,
                    p.updateCheckSpec,
                    p.patchExecSpec,
                    p.vulnerabilitySpec,
                    p.privilegeSpec,
                    p.lockSpec);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CloneProfileRequest(String newSlug, String newDisplayName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OsProfileDryRunRequest(UUID deviceId, OsProfileUpsertRequest profile) {}

    public record OsProfileDryRunResponse(
            boolean matchedDetectionRules,
            Map<String, String> osFacts,
            List<NormalizedPackage> samplePackages,
            int totalParsed,
            int skippedLines,
            List<String> sampleSkippedLines,
            int durationMs,
            String credentialUsername,
            boolean usedFallback) {}

    public record WorkerHealthStatusDto(
            boolean reachable, HealthResponse workerHealth, String errorMessage) {}
}

