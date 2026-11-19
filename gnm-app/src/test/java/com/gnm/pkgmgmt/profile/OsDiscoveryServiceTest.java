package com.gnm.pkgmgmt.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.PkgWorkerClient.AuthenticatedCallResult;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.ProbeResponse;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionCondition;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionRules;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OsDiscoveryServiceTest {

    @Test
    void respectsPinnedProfileWithoutOverwriting() {
        PkgWorkerClient workerClient = mock(PkgWorkerClient.class);
        OsDiscoveryService service =
                new OsDiscoveryService(workerClient, new OsProfileMatcher());

        OsProfile pinned = new OsProfile();
        pinned.id = UUID.randomUUID();
        pinned.slug = "custom-bsd";

        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();
        device.osProfile = pinned;
        device.osProfilePinned = true;

        OsProfile resolved =
                service.detectAndAssign(device, List.of(pinned));
        assertEquals("custom-bsd", resolved.slug);
        verify(workerClient, never()).probeDevice(any(), any());
    }

    @Test
    void persistsOsFactsEvenWhenNoProfileMatches() {
        PkgWorkerClient workerClient = mock(PkgWorkerClient.class);
        Map<String, String> facts = Map.of("kernel", "NetBSD", "os.id", "netbsd");
        when(workerClient.probeDevice(any(), any()))
                .thenReturn(
                        new AuthenticatedCallResult<>(
                                new ProbeResponse(facts, Map.of()),
                                UUID.randomUUID(),
                                "admin",
                                false,
                                "PKG_MANAGEMENT"));

        OsDiscoveryService service =
                new OsDiscoveryService(workerClient, new OsProfileMatcher());

        OsProfile linuxOnly = new OsProfile();
        linuxOnly.slug = "debian-apt";
        linuxOnly.enabled = true;
        linuxOnly.priority = 10;
        linuxOnly.detectionRules =
                new DetectionRules(
                        "ALL",
                        List.of(new DetectionCondition("kernel", "EQUALS", "Linux")),
                        null);

        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();

        OsProfile matched = service.detectAndAssign(device, List.of(linuxOnly));
        assertNull(matched);
        assertNotNull(device.osFacts);
        assertEquals("NetBSD", device.osFacts.get("kernel"));
        assertNotNull(device.osDetectedAt);
    }

    @Test
    void assignsMatchedProfileAndUpdatesDevice() {
        PkgWorkerClient workerClient = mock(PkgWorkerClient.class);
        Map<String, String> facts =
                Map.of(
                        "kernel", "Linux",
                        "os.id", "debian",
                        "os.id_or_like", "debian",
                        "os.version_major", "12");
        when(workerClient.probeDevice(any(), eq(Map.of("debian-apt", "command -v dpkg-query"))))
                .thenReturn(
                        new AuthenticatedCallResult<>(
                                new ProbeResponse(facts, Map.of("debian-apt", true)),
                                UUID.randomUUID(),
                                "lanalmanac",
                                false,
                                "PKG_MANAGEMENT"));

        OsDiscoveryService service =
                new OsDiscoveryService(workerClient, new OsProfileMatcher());

        OsProfile debian = new OsProfile();
        debian.id = UUID.randomUUID();
        debian.slug = "debian-apt";
        debian.enabled = true;
        debian.priority = 10;
        debian.detectionRules =
                new DetectionRules(
                        "ALL",
                        List.of(new DetectionCondition("os.id_or_like", "CONTAINS_TOKEN", "debian")),
                        "command -v dpkg-query");

        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();

        OsProfile matched = service.detectAndAssign(device, List.of(debian));
        assertNotNull(matched);
        assertEquals("debian-apt", matched.slug);
        assertEquals(debian, device.osProfile);
        assertEquals("Debian 12", device.osFamily + " " + device.osVersion);
    }
}

