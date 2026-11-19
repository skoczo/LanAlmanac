package com.gnm.pkgmgmt.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkerContractDtos {

    private WorkerContractDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiError(
            String errorCode,
            String message,
            String actualFingerprint,
            Integer exitCode,
            String details) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HealthResponse(
            String status,
            String contractVersion,
            String workerVersion,
            String implementation,
            List<String> supportedTransports,
            List<String> supportedParsers,
            List<String> supportedElevationModes) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuthConfig(String type, String secret, String sudoSecret) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SshTarget(
            String transport,
            String host,
            int port,
            String username,
            String expectedHostKeySha256,
            AuthConfig auth,
            int connectTimeoutSeconds) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PrivilegeSpec(
            String elevationMode,
            String wrapperTemplate,
            String passwordWrapperTemplate,
            List<String> sudoersAllowedCommands) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LockSpec(
            List<Integer> retryableExitCodes,
            String lockErrorPattern,
            Integer maxRetries,
            List<Integer> backoffSeconds) {}

    public record SshFingerprintRequest(String host, int port, int connectTimeoutSeconds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SshFingerprintResponse(String fingerprintSha256, String keyAlgorithm) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProbeRequest(
            UUID executionId, SshTarget target, Map<String, String> verifyCommands) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProbeResponse(
            Map<String, String> osFacts, Map<String, Boolean> verifyResults) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InventorySpec(
            String command,
            boolean requiresPrivilege,
            int timeoutSeconds,
            String parserType,
            Map<String, Object> parserConfig,
            String purlTemplate) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InventoryRequest(
            UUID executionId,
            SshTarget target,
            Map<String, String> environmentVars,
            Map<String, String> osFacts,
            InventorySpec inventorySpec,
            PrivilegeSpec privilegeSpec,
            Integer dryRunPreviewLimit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NormalizedPackage(
            String name,
            String version,
            String arch,
            String sourceName,
            String sourceVersion,
            String purl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InventoryResponse(
            List<NormalizedPackage> packages,
            int totalParsed,
            int skippedLines,
            List<String> sampleSkippedLines,
            int durationMs) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateCheckSpec(
            String refreshCommand,
            boolean refreshRequiresPrivilege,
            String checkCommand,
            boolean checkRequiresPrivilege,
            List<Integer> noUpdatesExitCodes,
            List<Integer> updatesAvailableExitCodes,
            int timeoutSeconds,
            String parserType,
            Map<String, Object> parserConfig) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateCheckRequest(
            UUID executionId,
            SshTarget target,
            Map<String, String> environmentVars,
            boolean skipRefresh,
            UpdateCheckSpec updateCheckSpec,
            PrivilegeSpec privilegeSpec,
            LockSpec lockSpec) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AvailableUpdate(
            String packageName,
            String arch,
            String currentVersion,
            String candidateVersion,
            boolean isSecurityUpdate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateCheckResponse(
            List<AvailableUpdate> updates, int exitCode, int retriesUsed, int durationMs) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PatchExecSpec(
            String preCheckCommand,
            String refreshCommand,
            String fullUpgradeCommand,
            String securityUpgradeCommand,
            String selectedUpgradeTemplate,
            String rebootCheckCommand,
            List<Integer> rebootRequiredExitCodes,
            boolean requiresPrivilege,
            Map<String, String> nonInteractiveEnv,
            int timeoutSeconds,
            int idleTimeoutSeconds) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PatchExecuteRequest(
            UUID executionId,
            SshTarget target,
            Map<String, String> environmentVars,
            String mode,
            List<String> packages,
            PatchExecSpec patchExecSpec,
            PrivilegeSpec privilegeSpec,
            LockSpec lockSpec) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PatchStreamFrame(
            String type,
            long seq,
            String ts,
            String phase,
            String stream,
            String line,
            String status,
            Integer exitCode,
            Boolean rebootRequired,
            String errorCode,
            String errorMessage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CancelResponse(UUID executionId, boolean cancelled) {}
}

