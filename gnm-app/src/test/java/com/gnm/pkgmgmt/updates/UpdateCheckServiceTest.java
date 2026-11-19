package com.gnm.pkgmgmt.updates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.client.DeviceLockManager;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.PkgWorkerClient.AuthenticatedCallResult;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.AvailableUpdate;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckSpec;
import com.gnm.pkgmgmt.profile.OsDiscoveryService;
import com.gnm.pkgmgmt.profile.OsProfile;
import com.gnm.pkgmgmt.vulnerability.DeviceVulnerability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UpdateCheckServiceTest {

    @Test
    void reconcilesAvailableUpdatesAndCorrelatesFixAvailableNowOnVulnerabilities() {
        PkgWorkerClient workerClient = mock(PkgWorkerClient.class);
        OsDiscoveryService discoveryService = mock(OsDiscoveryService.class);

        UpdateCheckService service =
                new UpdateCheckService(
                        new DeviceLockManager(), workerClient, discoveryService);

        OsProfile profile = new OsProfile();
        profile.slug = "debian-apt";
        profile.environmentVars = Map.of("LC_ALL", "C");
        profile.updateCheckSpec =
                new UpdateCheckSpec(
                        "apt-get update -qq",
                        true,
                        "apt-get -s upgrade",
                        false,
                        List.of(0),
                        List.of(0),
                        120,
                        "REGEX_LINE",
                        Map.of());

        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();
        device.osProfile = profile;

        List<AvailableUpdate> incoming =
                List.of(
                        new AvailableUpdate(
                                "libssl3",
                                "amd64",
                                "3.0.11-1~deb12u1",
                                "3.0.11-1~deb12u2",
                                true));
        when(workerClient.checkUpdates(any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(
                        new AuthenticatedCallResult<>(
                                new UpdateCheckResponse(incoming, 0, 1, 350),
                                UUID.randomUUID(),
                                "lanalmanac",
                                false,
                                "PKG_MANAGEMENT"));

        DeviceAvailableUpdate stale = new DeviceAvailableUpdate();
        stale.packageName = "already-upgraded";
        stale.arch = "amd64";
        stale.candidateVersion = "1.0.1";
        List<DeviceAvailableUpdate> updateStore = new ArrayList<>(List.of(stale));

        DeviceVulnerability openSslVuln = new DeviceVulnerability();
        openSslVuln.packageName = "libssl3";
        openSslVuln.status = "OPEN";
        openSslVuln.fixAvailableNow = false;

        DeviceVulnerability otherVuln = new DeviceVulnerability();
        otherVuln.packageName = "unpatched-pkg";
        otherVuln.status = "OPEN";
        otherVuln.fixAvailableNow = true;

        List<DeviceVulnerability> vulnStore = List.of(openSslVuln, otherVuln);

        service.executeCheckInMemory(device, false, updateStore, vulnStore);

        assertEquals(1, updateStore.size());
        assertEquals("libssl3", updateStore.get(0).packageName);
        assertEquals("3.0.11-1~deb12u2", updateStore.get(0).candidateVersion);
        assertTrue(updateStore.get(0).isSecurityUpdate);
        assertNotNull(device.updatesLastCheckedAt);

        assertTrue(openSslVuln.fixAvailableNow, "libssl3 has an update -> fixAvailableNow must be true");
        assertFalse(otherVuln.fixAvailableNow, "unpatched-pkg has no update -> fixAvailableNow must be false");
    }
}

