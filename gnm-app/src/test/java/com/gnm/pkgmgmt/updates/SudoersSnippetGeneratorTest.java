package com.gnm.pkgmgmt.updates;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PrivilegeSpec;
import com.gnm.pkgmgmt.profile.OsProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class SudoersSnippetGeneratorTest {

    private final SudoersSnippetGenerator generator = new SudoersSnippetGenerator();

    @Test
    void generatesLeastPrivilegeNopasswdSudoersSnippet() {
        OsProfile profile = new OsProfile();
        profile.slug = "debian-apt";
        profile.displayName = "Debian / Ubuntu (APT)";
        profile.privilegeSpec =
                new PrivilegeSpec(
                        "SUDO_NOPASSWD",
                        "sudo -n {{command}}",
                        null,
                        List.of(
                                "/usr/bin/apt-get update -qq",
                                "/usr/bin/apt-get -y -o DPkg::Lock::Timeout=60 upgrade"));

        String snippet = generator.generate("lanalmanac", profile);
        assertTrue(snippet.contains("Defaults:lanalmanac !requiretty"));
        assertTrue(
                snippet.contains(
                        "lanalmanac ALL=(root) NOPASSWD: /usr/bin/apt-get update -qq, /usr/bin/apt-get -y -o DPkg::Lock::Timeout=60 upgrade"));
    }

    @Test
    void rejectsInvalidUsernameOrRelativeCommands() {
        OsProfile profile = new OsProfile();
        profile.slug = "debian-apt";
        profile.privilegeSpec =
                new PrivilegeSpec(
                        "SUDO_NOPASSWD",
                        "sudo -n {{command}}",
                        null,
                        List.of("/usr/bin/apt-get update"));

        assertThrows(
                IllegalArgumentException.class,
                () -> generator.generate("bad user;ALL", profile));
        assertThrows(
                IllegalArgumentException.class,
                () -> generator.generate("root\nALL=(ALL) NOPASSWD: ALL", profile));

        OsProfile relativeCmdProfile = new OsProfile();
        relativeCmdProfile.slug = "bad";
        relativeCmdProfile.privilegeSpec =
                new PrivilegeSpec(
                        "SUDO_NOPASSWD", "sudo -n {{command}}", null, List.of("apt-get update"));
        assertThrows(
                IllegalArgumentException.class,
                () -> generator.generate("lanalmanac", relativeCmdProfile));
    }
}

