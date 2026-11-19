package com.gnm.pkgmgmt.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionCondition;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionRules;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OsProfileMatcherTest {

    private final OsProfileMatcher matcher = new OsProfileMatcher();

    private static OsProfile profile(String slug, int priority, DetectionRules rules) {
        OsProfile p = new OsProfile();
        p.slug = slug;
        p.displayName = slug;
        p.priority = priority;
        p.enabled = true;
        p.detectionRules = rules;
        return p;
    }

    private static List<OsProfile> standardProfiles() {
        return List.of(
                profile(
                        "debian-apt",
                        10,
                        new DetectionRules(
                                "ALL",
                                List.of(
                                        new DetectionCondition("kernel", "EQUALS", "Linux"),
                                        new DetectionCondition(
                                                "os.id_or_like", "CONTAINS_TOKEN", "debian")),
                                "command -v dpkg-query >/dev/null 2>&1")),
                profile(
                        "rhel-dnf",
                        20,
                        new DetectionRules(
                                "ALL",
                                List.of(
                                        new DetectionCondition("kernel", "EQUALS", "Linux"),
                                        new DetectionCondition(
                                                "os.id_or_like",
                                                "IN",
                                                "rhel,rocky,almalinux,centos,fedora")),
                                "command -v rpm >/dev/null 2>&1")),
                profile(
                        "alpine-apk",
                        30,
                        new DetectionRules(
                                "ALL",
                                List.of(
                                        new DetectionCondition("kernel", "EQUALS", "Linux"),
                                        new DetectionCondition(
                                                "os.id_or_like", "CONTAINS_TOKEN", "alpine")),
                                "test -f /lib/apk/db/installed")));
    }

    @Test
    void matchesDebianUbuntuRockyAlmaAndAlpineWithoutHardcodedDistroSwitch() {
        List<OsProfile> profiles = standardProfiles();

        Optional<OsProfile> debian =
                matcher.findMatch(
                        profiles,
                        Map.of(
                                "kernel", "Linux",
                                "os.id", "debian",
                                "os.id_or_like", "debian",
                                "os.version_major", "12"),
                        Map.of("debian-apt", true));
        assertTrue(debian.isPresent());
        assertEquals("debian-apt", debian.get().slug);

        Optional<OsProfile> ubuntu =
                matcher.findMatch(
                        profiles,
                        Map.of(
                                "kernel", "Linux",
                                "os.id", "ubuntu",
                                "os.id_like", "debian",
                                "os.id_or_like", "ubuntu debian",
                                "os.version_id", "24.04"),
                        Map.of("debian-apt", true));
        assertTrue(ubuntu.isPresent());
        assertEquals("debian-apt", ubuntu.get().slug);

        Optional<OsProfile> rocky =
                matcher.findMatch(
                        profiles,
                        Map.of(
                                "kernel", "Linux",
                                "os.id", "rocky",
                                "os.id_or_like", "rocky rhel centos fedora",
                                "os.version_major", "9"),
                        Map.of("rhel-dnf", true));
        assertTrue(rocky.isPresent());
        assertEquals("rhel-dnf", rocky.get().slug);

        Optional<OsProfile> alpine =
                matcher.findMatch(
                        profiles,
                        Map.of(
                                "kernel", "Linux",
                                "os.id", "alpine",
                                "os.id_or_like", "alpine",
                                "os.version_major_minor", "3.20"),
                        Map.of("alpine-apk", true));
        assertTrue(alpine.isPresent());
        assertEquals("alpine-apk", alpine.get().slug);
    }

    @Test
    void returnsEmptyForUnknownOsOrFailedVerifyCommand() {
        List<OsProfile> profiles = standardProfiles();

        Optional<OsProfile> unknown =
                matcher.findMatch(
                        profiles,
                        Map.of("kernel", "FreeBSD", "os.id", "freebsd", "os.id_or_like", "freebsd"),
                        Map.of());
        assertTrue(unknown.isEmpty());

        Optional<OsProfile> failedVerify =
                matcher.findMatch(
                        profiles,
                        Map.of("kernel", "Linux", "os.id_or_like", "debian"),
                        Map.of("debian-apt", false));
        assertTrue(failedVerify.isEmpty());
    }
}

