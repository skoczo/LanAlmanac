package com.gnm.pkgmgmt.profile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventorySpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.LockSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchExecSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PrivilegeSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckSpec;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionCondition;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionRules;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.VulnerabilitySpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileValidatorTest {

    private final ProfileValidator validator = new ProfileValidator();

    private static OsProfile validDebianProfile() {
        OsProfile p = new OsProfile();
        p.slug = "debian-apt";
        p.displayName = "Debian / Ubuntu (APT)";
        p.transportType = "SSH";
        p.priority = 10;
        p.enabled = true;
        p.detectionRules =
                new DetectionRules(
                        "ALL",
                        List.of(
                                new DetectionCondition("kernel", "EQUALS", "Linux"),
                                new DetectionCondition("os.id_or_like", "CONTAINS_TOKEN", "debian")),
                        "command -v dpkg-query >/dev/null 2>&1");
        p.environmentVars = Map.of("LC_ALL", "C", "LANG", "C");
        p.inventorySpec =
                new InventorySpec(
                        "dpkg-query -W -f='${Status}\\t${Package}\\t${Version}\\t${Architecture}\\t${source:Package}\\t${source:Version}\\n'",
                        false,
                        60,
                        "DELIMITED",
                        Map.of("delimiter", "\t"),
                        "pkg:deb/{{os.id}}/{{name}}@{{version}}?arch={{arch}}");
        p.updateCheckSpec =
                new UpdateCheckSpec(
                        "apt-get update -qq",
                        true,
                        "apt-get -s -o Debug::NoLocking=true upgrade",
                        false,
                        List.of(0),
                        List.of(0),
                        120,
                        "REGEX_LINE",
                        Map.of(
                                "pattern",
                                "^Inst\\s+(?<packageName>\\S+)\\s+\\[(?<currentVersion>[^\\]]+)\\]\\s+\\((?<candidateVersion>\\S+)"));
        p.patchExecSpec =
                new PatchExecSpec(
                        null,
                        "apt-get update -qq",
                        "apt-get -y -o DPkg::Lock::Timeout=60 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade",
                        null,
                        "apt-get -y -o DPkg::Lock::Timeout=60 install --only-upgrade -- {{packages}}",
                        "test -f /var/run/reboot-required",
                        List.of(0),
                        true,
                        Map.of("DEBIAN_FRONTEND", "noninteractive"),
                        1800,
                        300);
        p.vulnerabilitySpec = new VulnerabilitySpec(true, "Debian:{{os.version_major}}", "SOURCE_PACKAGE");
        p.privilegeSpec =
                new PrivilegeSpec(
                        "SUDO_NOPASSWD",
                        "sudo -n {{command}}",
                        null,
                        List.of("/usr/bin/apt-get update -qq"));
        p.lockSpec = new LockSpec(List.of(100), "Could not get lock", 3, List.of(15, 30, 60));
        return p;
    }

    @Test
    void acceptsValidProfile() {
        assertDoesNotThrow(() -> validator.validate(validDebianProfile()));
    }

    @Test
    void rejectsInvalidSlugOrForbiddenPlaceholders() {
        OsProfile badSlug = validDebianProfile();
        badSlug.slug = "Invalid Slug With Spaces!";
        assertThrows(IllegalArgumentException.class, () -> validator.validate(badSlug));

        OsProfile packagesInInventory = validDebianProfile();
        packagesInInventory.inventorySpec =
                new InventorySpec(
                        "dpkg-query {{packages}}",
                        false,
                        60,
                        "DELIMITED",
                        Map.of(),
                        "pkg:deb/{{os.id}}/{{name}}@{{version}}");
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> validator.validate(packagesInInventory));
        assertTrue(ex.getMessage().contains("placeholder"));
    }

    @Test
    void rejectsInvalidOrCatastrophicRegexAndOutOfRangeTimeouts() {
        OsProfile badRegex = validDebianProfile();
        badRegex.updateCheckSpec =
                new UpdateCheckSpec(
                        null,
                        false,
                        "apt-get -s upgrade",
                        false,
                        List.of(0),
                        List.of(0),
                        120,
                        "REGEX_LINE",
                        Map.of("pattern", "[unclosed-group"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(badRegex));

        OsProfile tooLongRegex = validDebianProfile();
        tooLongRegex.updateCheckSpec =
                new UpdateCheckSpec(
                        null,
                        false,
                        "apt-get -s upgrade",
                        false,
                        List.of(0),
                        List.of(0),
                        120,
                        "REGEX_LINE",
                        Map.of("pattern", "a".repeat(600)));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(tooLongRegex));

        OsProfile badTimeout = validDebianProfile();
        badTimeout.inventorySpec =
                new InventorySpec(
                        "dpkg-query -W",
                        false,
                        9999,
                        "DELIMITED",
                        Map.of(),
                        "pkg:deb/{{os.id}}/{{name}}@{{version}}");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(badTimeout));
    }
}

