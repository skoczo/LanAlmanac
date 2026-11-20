package com.gnm.pkgmgmt.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gnm.pkgmgmt.inventory.DevicePackage;
import com.gnm.pkgmgmt.inventory.DevicePackageScan;
import com.gnm.pkgmgmt.patching.PatchJob;
import com.gnm.pkgmgmt.patching.PatchJobLog;
import com.gnm.pkgmgmt.schedule.DevicePackageSchedule;
import com.gnm.pkgmgmt.updates.DeviceAvailableUpdate;
import com.gnm.pkgmgmt.vulnerability.DeviceVulnerability;
import com.gnm.pkgmgmt.vulnerability.VulnerabilityAdvisory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PackageManagementDtos {

    private PackageManagementDtos() {}

    public record DeviceOsProfileStatusDto(
            UUID osProfileId,
            String osProfileSlug,
            String osProfileName,
            boolean pinned,
            Map<String, String> osFacts,
            Instant osDetectedAt,
            String effectiveCredentialUsername,
            String effectiveCredentialSource,
            boolean sshHostKeyTrusted) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeviceOsProfileAssignmentRequest(UUID osProfileId, boolean pinned) {}

    public record DevicePackageDto(
            UUID id,
            String name,
            String version,
            String arch,
            String sourceName,
            String sourceVersion,
            String ecosystem,
            String purl,
            Instant lastSeenAt,
            int openVulnerabilityCount,
            String candidateVersion,
            boolean isSecurityUpdate) {

        public static DevicePackageDto fromEntity(DevicePackage p, int openVulnCount) {
            return fromEntity(p, openVulnCount, null, false);
        }

        public static DevicePackageDto fromEntity(
                DevicePackage p,
                int openVulnCount,
                String candidateVersion,
                boolean isSecurityUpdate) {
            return new DevicePackageDto(
                    p.id,
                    p.name,
                    p.version,
                    p.arch,
                    p.sourceName,
                    p.sourceVersion,
                    p.ecosystem,
                    p.purl,
                    p.lastSeenAt,
                    openVulnCount,
                    candidateVersion,
                    isSecurityUpdate);
        }
    }

    public record DevicePackageScanDto(
            UUID id,
            UUID deviceId,
            String osProfileSlug,
            String credentialUsername,
            boolean usedFallback,
            String status,
            int packageCount,
            int skippedLines,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt) {

        public static DevicePackageScanDto fromEntity(DevicePackageScan s) {
            return new DevicePackageScanDto(
                    s.id,
                    s.physicalDevice != null ? s.physicalDevice.id : null,
                    s.osProfile != null ? s.osProfile.slug : null,
                    s.credentialUsername,
                    s.usedFallback,
                    s.status,
                    s.packageCount,
                    s.skippedLines,
                    s.errorCode,
                    s.errorMessage,
                    s.startedAt,
                    s.finishedAt);
        }
    }

    public record DeviceVulnerabilityDto(
            UUID id,
            UUID deviceId,
            String deviceName,
            String packageName,
            String installedVersion,
            String fixedVersion,
            boolean fixAvailableNow,
            String advisoryId,
            List<String> cveAliases,
            String severity,
            Double cvssScore,
            String summary,
            List<String> references,
            String status,
            String suppressionNote,
            String suppressedBy,
            Instant firstDetectedAt,
            Instant lastDetectedAt,
            Instant resolvedAt) {

        public static DeviceVulnerabilityDto fromEntity(DeviceVulnerability v) {
            VulnerabilityAdvisory adv = v.advisory;
            return new DeviceVulnerabilityDto(
                    v.id,
                    v.physicalDevice != null ? v.physicalDevice.id : null,
                    v.physicalDevice != null ? v.physicalDevice.displayName : null,
                    v.packageName,
                    v.installedVersion,
                    v.fixedVersion,
                    v.fixAvailableNow,
                    adv != null ? adv.id : null,
                    adv != null && adv.cveAliases != null ? adv.cveAliases : List.of(),
                    adv != null ? adv.severity : "UNKNOWN",
                    adv != null ? adv.cvssScore : null,
                    adv != null ? adv.summary : null,
                    adv != null && adv.references != null ? adv.references : List.of(),
                    v.status,
                    v.suppressionNote,
                    v.suppressedBy,
                    v.firstDetectedAt,
                    v.lastDetectedAt,
                    v.resolvedAt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VulnerabilityStatusUpdateRequest(String status, String suppressionNote) {}

    public record DeviceAvailableUpdateDto(
            UUID id,
            String packageName,
            String arch,
            String currentVersion,
            String candidateVersion,
            boolean isSecurityUpdate,
            List<String> fixedAdvisories,
            Instant checkedAt) {

        public static DeviceAvailableUpdateDto fromEntity(
                DeviceAvailableUpdate u, List<String> fixedAdvisories) {
            return new DeviceAvailableUpdateDto(
                    u.id,
                    u.packageName,
                    u.arch,
                    u.currentVersion,
                    u.candidateVersion,
                    u.isSecurityUpdate,
                    fixedAdvisories != null ? fixedAdvisories : List.of(),
                    u.checkedAt);
        }
    }

    public record SudoersSnippetDto(
            String username, String osProfileSlug, String osProfileName, String snippet) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PatchJobCreateRequest(String mode, List<String> packages) {}

    public record PatchJobDto(
            UUID id,
            UUID deviceId,
            String osProfileSlug,
            UUID credentialId,
            String credentialUsername,
            boolean usedFallback,
            String triggeredBy,
            String mode,
            List<String> requestedPackages,
            String status,
            String currentPhase,
            Boolean rebootRequired,
            Integer exitCode,
            String errorCode,
            String errorSummary,
            Instant startedAt,
            Instant finishedAt,
            Instant createdAt) {

        public static PatchJobDto fromEntity(PatchJob j) {
            return new PatchJobDto(
                    j.id,
                    j.physicalDevice != null ? j.physicalDevice.id : null,
                    j.osProfile != null ? j.osProfile.slug : null,
                    j.credentialId,
                    j.credentialUsername,
                    j.usedFallback,
                    j.triggeredBy,
                    j.mode,
                    j.requestedPackages != null ? j.requestedPackages : List.of(),
                    j.status,
                    j.currentPhase,
                    j.rebootRequired,
                    j.exitCode,
                    j.errorCode,
                    j.errorSummary,
                    j.startedAt,
                    j.finishedAt,
                    j.createdAt);
        }
    }

    public record PatchJobLogDto(long seq, String stream, Instant ts, String line) {
        public static PatchJobLogDto fromEntity(PatchJobLog l) {
            return new PatchJobLogDto(l.seq, l.stream, l.ts, l.line);
        }
    }

    public record PatchJobDetailDto(PatchJobDto job, List<PatchJobLogDto> logs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DevicePackageScheduleUpdateRequest(
            boolean inventoryEnabled,
            String inventoryCron,
            boolean updateCheckEnabled,
            String updateCheckCron,
            boolean autoPatchEnabled,
            String autoPatchMode,
            String autoPatchCron,
            String timezone) {}

    public record DevicePackageScheduleDto(
            UUID deviceId,
            boolean inventoryEnabled,
            String inventoryCron,
            Instant nextInventoryRunAt,
            Instant lastInventoryRunAt,
            boolean updateCheckEnabled,
            String updateCheckCron,
            Instant nextUpdateCheckRunAt,
            Instant lastUpdateCheckRunAt,
            boolean autoPatchEnabled,
            String autoPatchMode,
            String autoPatchCron,
            Instant nextAutoPatchRunAt,
            Instant lastAutoPatchRunAt,
            String timezone,
            String updatedBy,
            Instant updatedAt,
            List<Instant> nextPreviewRuns) {

        public static DevicePackageScheduleDto fromEntity(
                DevicePackageSchedule s, List<Instant> previewRuns) {
            return new DevicePackageScheduleDto(
                    s.deviceId,
                    s.inventoryEnabled,
                    s.inventoryCron,
                    s.nextInventoryRunAt,
                    s.lastInventoryRunAt,
                    s.updateCheckEnabled,
                    s.updateCheckCron,
                    s.nextUpdateCheckRunAt,
                    s.lastUpdateCheckRunAt,
                    s.autoPatchEnabled,
                    s.autoPatchMode,
                    s.autoPatchCron,
                    s.nextAutoPatchRunAt,
                    s.lastAutoPatchRunAt,
                    s.timezone,
                    s.updatedBy,
                    s.updatedAt,
                    previewRuns != null ? previewRuns : List.of());
        }
    }
}

