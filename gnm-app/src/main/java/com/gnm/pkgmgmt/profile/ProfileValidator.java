package com.gnm.pkgmgmt.profile;

import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventorySpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.LockSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchExecSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PrivilegeSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckSpec;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionCondition;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionRules;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.VulnerabilitySpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class ProfileValidator {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,62}$");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    private static final int MAX_REGEX_LENGTH = 512;

    private static final Set<String> VALID_PARSERS =
            Set.of("DELIMITED", "KEY_VALUE_BLOCKS", "JSON_MAPPING", "REGEX_LINE");
    private static final Set<String> VALID_ELEVATION_MODES =
            Set.of("NONE", "SUDO_NOPASSWD", "SUDO_PASSWORD");
    private static final Set<String> VALID_CONDITION_OPS =
            Set.of("EQUALS", "NOT_EQUALS", "IN", "CONTAINS_TOKEN", "PREFIX", "REGEX");
    private static final Set<String> ALLOWED_PURL_PLACEHOLDERS =
            Set.of(
                    "os.id",
                    "os.version_id",
                    "os.version_major",
                    "os.version_major_minor",
                    "os.codename",
                    "name",
                    "version",
                    "arch",
                    "sourceName",
                    "sourceVersion");
    private static final Set<String> ALLOWED_OSV_PLACEHOLDERS =
            Set.of("os.id", "os.version_id", "os.version_major", "os.version_major_minor", "os.codename");

    public void validate(OsProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("OS profile is required");
        }
        if (profile.slug == null || !SLUG_PATTERN.matcher(profile.slug).matches()) {
            throw new IllegalArgumentException(
                    "Profile slug must match ^[a-z0-9][a-z0-9_-]{1,62}$");
        }
        if (profile.displayName == null || profile.displayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (!"SSH".equals(profile.transportType)) {
            throw new IllegalArgumentException("transportType must be SSH");
        }
        if (profile.priority < 1 || profile.priority > 10000) {
            throw new IllegalArgumentException("priority must be between 1 and 10000");
        }

        validateDetectionRules(profile.detectionRules);
        validateInventorySpec(profile.inventorySpec);
        validateUpdateCheckSpec(profile.updateCheckSpec);
        validatePatchExecSpec(profile.patchExecSpec);
        validateVulnerabilitySpec(profile.vulnerabilitySpec);
        validatePrivilegeSpec(profile.privilegeSpec);
        validateLockSpec(profile.lockSpec);
    }

    private void validateDetectionRules(DetectionRules rules) {
        if (rules == null || rules.conditions() == null || rules.conditions().isEmpty()) {
            throw new IllegalArgumentException("detectionRules must contain at least one condition");
        }
        for (DetectionCondition c : rules.conditions()) {
            if (c.fact() == null || c.fact().isBlank()) {
                throw new IllegalArgumentException("detection condition fact is required");
            }
            if (c.op() == null || !VALID_CONDITION_OPS.contains(c.op())) {
                throw new IllegalArgumentException("Unsupported detection condition operator: " + c.op());
            }
            if ("REGEX".equals(c.op())) {
                validateRegex(c.value(), "detectionRules.condition");
            }
        }
        validateNoPlaceholders(rules.verifyCommand(), "detectionRules.verifyCommand");
    }

    private void validateInventorySpec(InventorySpec spec) {
        if (spec == null || spec.command() == null || spec.command().isBlank()) {
            throw new IllegalArgumentException("inventorySpec.command is required");
        }
        validateNoPlaceholders(spec.command(), "inventorySpec.command");
        if (spec.timeoutSeconds() < 5 || spec.timeoutSeconds() > 300) {
            throw new IllegalArgumentException(
                    "inventorySpec.timeoutSeconds must be between 5 and 300");
        }
        validateParser(spec.parserType(), spec.parserConfig(), "inventorySpec");
        if (spec.purlTemplate() == null || !spec.purlTemplate().startsWith("pkg:")) {
            throw new IllegalArgumentException("inventorySpec.purlTemplate must start with pkg:");
        }
        validateAllowedPlaceholders(
                spec.purlTemplate(), ALLOWED_PURL_PLACEHOLDERS, "inventorySpec.purlTemplate");
    }

    private void validateUpdateCheckSpec(UpdateCheckSpec spec) {
        if (spec == null || spec.checkCommand() == null || spec.checkCommand().isBlank()) {
            throw new IllegalArgumentException("updateCheckSpec.checkCommand is required");
        }
        validateNoPlaceholders(spec.refreshCommand(), "updateCheckSpec.refreshCommand");
        validateNoPlaceholders(spec.checkCommand(), "updateCheckSpec.checkCommand");
        if (spec.timeoutSeconds() < 10 || spec.timeoutSeconds() > 600) {
            throw new IllegalArgumentException(
                    "updateCheckSpec.timeoutSeconds must be between 10 and 600");
        }
        validateParser(spec.parserType(), spec.parserConfig(), "updateCheckSpec");
    }

    private void validatePatchExecSpec(PatchExecSpec spec) {
        if (spec == null || spec.fullUpgradeCommand() == null || spec.fullUpgradeCommand().isBlank()) {
            throw new IllegalArgumentException("patchExecSpec.fullUpgradeCommand is required");
        }
        validateNoPlaceholders(spec.preCheckCommand(), "patchExecSpec.preCheckCommand");
        validateNoPlaceholders(spec.refreshCommand(), "patchExecSpec.refreshCommand");
        validateNoPlaceholders(spec.fullUpgradeCommand(), "patchExecSpec.fullUpgradeCommand");
        validateNoPlaceholders(spec.securityUpgradeCommand(), "patchExecSpec.securityUpgradeCommand");
        validateNoPlaceholders(spec.rebootCheckCommand(), "patchExecSpec.rebootCheckCommand");

        if (spec.selectedUpgradeTemplate() != null && !spec.selectedUpgradeTemplate().isBlank()) {
            validateAllowedPlaceholders(
                    spec.selectedUpgradeTemplate(),
                    Set.of("packages"),
                    "patchExecSpec.selectedUpgradeTemplate");
            if (!spec.selectedUpgradeTemplate().contains("{{packages}}")) {
                throw new IllegalArgumentException(
                        "patchExecSpec.selectedUpgradeTemplate must contain {{packages}}");
            }
        }
        if (spec.timeoutSeconds() < 60 || spec.timeoutSeconds() > 3600) {
            throw new IllegalArgumentException(
                    "patchExecSpec.timeoutSeconds must be between 60 and 3600");
        }
        if (spec.idleTimeoutSeconds() < 30 || spec.idleTimeoutSeconds() > 1800) {
            throw new IllegalArgumentException(
                    "patchExecSpec.idleTimeoutSeconds must be between 30 and 1800");
        }
    }

    private void validateVulnerabilitySpec(VulnerabilitySpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("vulnerabilitySpec is required");
        }
        if (spec.enabled()) {
            if (spec.osvEcosystemTemplate() == null || spec.osvEcosystemTemplate().isBlank()) {
                throw new IllegalArgumentException(
                        "vulnerabilitySpec.osvEcosystemTemplate is required when enabled");
            }
            validateAllowedPlaceholders(
                    spec.osvEcosystemTemplate(),
                    ALLOWED_OSV_PLACEHOLDERS,
                    "vulnerabilitySpec.osvEcosystemTemplate");
            if (!"SOURCE_PACKAGE".equals(spec.queryField())
                    && !"BINARY_PACKAGE".equals(spec.queryField())) {
                throw new IllegalArgumentException(
                        "vulnerabilitySpec.queryField must be SOURCE_PACKAGE or BINARY_PACKAGE");
            }
        }
    }

    private void validatePrivilegeSpec(PrivilegeSpec spec) {
        if (spec == null
                || spec.elevationMode() == null
                || !VALID_ELEVATION_MODES.contains(spec.elevationMode())) {
            throw new IllegalArgumentException(
                    "privilegeSpec.elevationMode must be one of " + VALID_ELEVATION_MODES);
        }
        if (spec.wrapperTemplate() != null && !spec.wrapperTemplate().isBlank()) {
            validateAllowedPlaceholders(
                    spec.wrapperTemplate(), Set.of("command"), "privilegeSpec.wrapperTemplate");
        }
    }

    private void validateLockSpec(LockSpec spec) {
        if (spec == null) {
            return;
        }
        if (spec.lockErrorPattern() != null && !spec.lockErrorPattern().isBlank()) {
            validateRegex(spec.lockErrorPattern(), "lockSpec.lockErrorPattern");
        }
    }

    private void validateParser(
            String parserType, Map<String, Object> parserConfig, String fieldPrefix) {
        if (parserType == null || !VALID_PARSERS.contains(parserType)) {
            throw new IllegalArgumentException(
                    fieldPrefix + ".parserType must be one of " + VALID_PARSERS);
        }
        if ("REGEX_LINE".equals(parserType)) {
            Object pat = parserConfig != null ? parserConfig.get("pattern") : null;
            if (!(pat instanceof String patternStr) || patternStr.isBlank()) {
                throw new IllegalArgumentException(
                        fieldPrefix + ".parserConfig.pattern is required for REGEX_LINE");
            }
            validateRegex(patternStr, fieldPrefix + ".parserConfig.pattern");
        }
    }

    private static void validateRegex(String pattern, String fieldName) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException(fieldName + " regex cannot be blank");
        }
        if (pattern.length() > MAX_REGEX_LENGTH) {
            throw new IllegalArgumentException(
                    fieldName + " regex exceeds maximum length of " + MAX_REGEX_LENGTH);
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex in " + fieldName + ": " + e.getMessage());
        }
    }

    private static void validateNoPlaceholders(String value, String fieldName) {
        validateAllowedPlaceholders(value, Set.of(), fieldName);
    }

    private static void validateAllowedPlaceholders(
            String value, Set<String> allowed, String fieldName) {
        if (value == null || value.isEmpty()) {
            return;
        }
        Matcher m = PLACEHOLDER_PATTERN.matcher(value);
        while (m.find()) {
            String key = m.group(1).trim();
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                        "Forbidden placeholder '{{" + key + "}}' in " + fieldName);
            }
        }
    }
}

