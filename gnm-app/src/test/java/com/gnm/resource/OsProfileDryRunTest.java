package com.gnm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.PkgWorkerClient.AuthenticatedCallResult;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventoryResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventorySpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.LockSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.NormalizedPackage;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchExecSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PrivilegeSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.ProbeResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckSpec;
import com.gnm.pkgmgmt.dto.OsProfileDtos.OsProfileDryRunResponse;
import com.gnm.pkgmgmt.dto.OsProfileDtos.OsProfileUpsertRequest;
import com.gnm.pkgmgmt.profile.OsProfileMatcher;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionCondition;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionRules;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.VulnerabilitySpec;
import com.gnm.pkgmgmt.profile.ProfileValidator;
import jakarta.annotation.security.RolesAllowed;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OsProfileDryRunTest {

    @Test
    void enforcesGnmAdminRoleOnDryRunEndpoint() {
        Method dryRunMethod =
                Arrays.stream(OsProfileResource.class.getDeclaredMethods())
                        .filter(m -> m.getName().equals("dryRunProfile"))
                        .findFirst()
                        .orElseThrow();
        RolesAllowed roles = dryRunMethod.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertEquals(List.of("gnm-admin"), Arrays.asList(roles.value()));
    }

    @Test
    void executesReadOnlyProbeAndInventoryWithPreviewLimit50WithoutMutatingDatabase() {
        PkgWorkerClient workerClient = mock(PkgWorkerClient.class);
        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();

        Map<String, String> facts = Map.of("kernel", "Linux", "id", "ubuntu", "version_major", "24");
        when(workerClient.probeDevice(eq(device), any()))
                .thenReturn(
                        new AuthenticatedCallResult<>(
                                new ProbeResponse(facts, Map.of("custom-apt", true)),
                                UUID.randomUUID(),
                                "pkg-bot",
                                false,
                                "PKG_MANAGEMENT"));

        NormalizedPackage samplePkg =
                new NormalizedPackage(
                        "openssl",
                        "3.0.13-0ubuntu3",
                        "amd64",
                        "openssl",
                        "3.0.13-0ubuntu3",
                        "pkg:deb/ubuntu/openssl@3.0.13-0ubuntu3");
        when(workerClient.collectInventory(
                        eq(device), any(), eq(facts), any(), any(), eq(50)))
                .thenReturn(
                        new AuthenticatedCallResult<>(
                                new InventoryResponse(
                                        List.of(samplePkg), 312, 2, List.of("malformed line 1"), 45),
                                UUID.randomUUID(),
                                "pkg-bot",
                                false,
                                "PKG_MANAGEMENT"));

        OsProfileResource resource =
                new OsProfileResource(
                        new ProfileValidator(), new OsProfileMatcher(), workerClient, null);

        OsProfileUpsertRequest req =
                new OsProfileUpsertRequest(
                        "custom-apt",
                        "Custom APT",
                        "SSH",
                        5,
                        true,
                        new DetectionRules(
                                "ALL",
                                List.of(new DetectionCondition("kernel", "EQUALS", "Linux")),
                                "command -v dpkg-query"),
                        Map.of("LC_ALL", "C"),
                        new InventorySpec(
                                "dpkg-query -W",
                                false,
                                60,
                                "DELIMITED",
                                Map.of(),
                                "pkg:deb/{{os.id}}/{{name}}@{{version}}"),
                        new UpdateCheckSpec(
                                null,
                                false,
                                "apt-get -s upgrade",
                                false,
                                List.of(0),
                                List.of(0),
                                120,
                                "DELIMITED",
                                Map.of()),
                        new PatchExecSpec(
                                null,
                                null,
                                "apt-get -y upgrade",
                                null,
                                null,
                                null,
                                List.of(0),
                                true,
                                Map.of(),
                                600,
                                120),
                        new VulnerabilitySpec(true, "Ubuntu:24.04", "SOURCE_PACKAGE"),
                        new PrivilegeSpec("SUDO_NOPASSWD", "sudo -n {{command}}", null, List.of()),
                        new LockSpec(List.of(100), "lock", 3, List.of(15)));

        OsProfileDryRunResponse resp = resource.executeDryRun(device, req);
        assertTrue(resp.matchedDetectionRules());
        assertEquals(312, resp.totalParsed());
        assertEquals(1, resp.samplePackages().size());
        assertEquals("openssl", resp.samplePackages().getFirst().name());
        assertEquals(2, resp.skippedLines());
        assertEquals("pkg-bot", resp.credentialUsername());

        verify(workerClient).collectInventory(eq(device), any(), eq(facts), any(), any(), eq(50));
    }
}

