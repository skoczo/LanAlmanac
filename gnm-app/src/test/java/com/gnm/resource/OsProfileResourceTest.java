package com.gnm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gnm.model.Credential;
import com.gnm.model.enums.CredentialCategory;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventorySpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.LockSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchExecSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PrivilegeSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckSpec;
import com.gnm.pkgmgmt.dto.OsProfileDtos.OsProfileDto;
import com.gnm.pkgmgmt.dto.OsProfileDtos.OsProfileExportDto;
import com.gnm.pkgmgmt.dto.OsProfileDtos.OsProfileUpsertRequest;
import com.gnm.pkgmgmt.profile.OsProfile;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionCondition;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionRules;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.VulnerabilitySpec;
import com.gnm.pkgmgmt.profile.ProfileValidator;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OsProfileResourceTest {

    @Test
    void enforcesExplicitRolesAllowedAnnotationsOnClassAndMutatingMethods() throws Exception {
        RolesAllowed classRoles = OsProfileResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(classRoles, "OsProfileResource must have class-level @RolesAllowed");
        assertEquals(
                Set.of("gnm-admin", "gnm-operator", "gnm-viewer"),
                Set.copyOf(Arrays.asList(classRoles.value())));

        for (String methodName :
                List.of(
                        "createProfile",
                        "updateProfile",
                        "deleteProfile",
                        "cloneProfile",
                        "importProfile")) {
            Method m =
                    Arrays.stream(OsProfileResource.class.getDeclaredMethods())
                            .filter(method -> method.getName().equals(methodName))
                            .findFirst()
                            .orElseThrow();
            RolesAllowed methodRoles = m.getAnnotation(RolesAllowed.class);
            assertNotNull(
                    methodRoles, "Mutating method " + methodName + " must restrict @RolesAllowed");
            assertEquals(List.of("gnm-admin"), Arrays.asList(methodRoles.value()));
        }
    }

    @Test
    void exportAndImportSchemaVersionAndValidation() {
        OsProfileResource resource =
                new OsProfileResource(new ProfileValidator(), null, null, null);

        OsProfile entity = new OsProfile();
        entity.id = UUID.randomUUID();
        entity.slug = "debian-apt";
        entity.displayName = "Debian APT";
        entity.transportType = "SSH";
        entity.priority = 10;
        entity.isSystem = true;
        entity.detectionRules =
                new DetectionRules(
                        "ALL",
                        List.of(new DetectionCondition("kernel", "EQUALS", "Linux")),
                        null);
        entity.inventorySpec =
                new InventorySpec(
                        "dpkg-query -W",
                        false,
                        60,
                        "DELIMITED",
                        Map.of(),
                        "pkg:deb/{{os.id}}/{{name}}@{{version}}");
        entity.updateCheckSpec =
                new UpdateCheckSpec(
                        null,
                        false,
                        "apt-get -s upgrade",
                        false,
                        List.of(0),
                        List.of(0),
                        120,
                        "DELIMITED",
                        Map.of());
        entity.patchExecSpec =
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
                        120);
        entity.vulnerabilitySpec = new VulnerabilitySpec(true, "Debian:{{os.version_major}}", "SOURCE_PACKAGE");
        entity.privilegeSpec = new PrivilegeSpec("SUDO_NOPASSWD", "sudo -n {{command}}", null, List.of());
        entity.lockSpec = new LockSpec(List.of(100), "lock", 3, List.of(15));

        OsProfileExportDto exported = OsProfileExportDto.fromEntity(entity);
        assertEquals(1, exported.schemaVersion());
        assertEquals("debian-apt", exported.slug());

        OsProfileDto dto = OsProfileDto.fromEntity(entity);
        assertTrue(dto.isSystem());
        assertEquals(entity.id, dto.id());

        // Unsupported schemaVersion -> 400
        OsProfileExportDto badVersion =
                new OsProfileExportDto(
                        99,
                        "custom",
                        "Custom",
                        "SSH",
                        50,
                        entity.detectionRules,
                        entity.environmentVars,
                        entity.inventorySpec,
                        entity.updateCheckSpec,
                        entity.patchExecSpec,
                        entity.vulnerabilitySpec,
                        entity.privilegeSpec,
                        entity.lockSpec);
        Response importResp = resource.importProfile(badVersion);
        assertEquals(400, importResp.getStatus());

        // Invalid profile payload -> 400 before DB access
        Response badCreate =
                resource.createProfile(
                        new OsProfileUpsertRequest(
                                "INVALID SLUG!",
                                "Bad",
                                "SSH",
                                10,
                                true,
                                entity.detectionRules,
                                entity.environmentVars,
                                entity.inventorySpec,
                                entity.updateCheckSpec,
                                entity.patchExecSpec,
                                entity.vulnerabilitySpec,
                                entity.privilegeSpec,
                                entity.lockSpec));
        assertEquals(400, badCreate.getStatus());
    }

    @Test
    void credentialCategoriesParsingAndExclusiveCategoryEnforcement() {
        Set<CredentialCategory> parsed =
                CredentialResource.parseCategories(
                        List.of("ssh_terminal", "PKG_MANAGEMENT"));
        assertEquals(
                Set.of(CredentialCategory.SSH_TERMINAL, CredentialCategory.PKG_MANAGEMENT),
                parsed);

        assertThrows(
                IllegalArgumentException.class,
                () -> CredentialResource.parseCategories(List.of("NON_EXISTENT_CATEGORY")));

        Credential existing = new Credential();
        existing.id = UUID.randomUUID();
        existing.label = "Old Pkg Key";
        existing.categories =
                new HashSet<>(
                        Set.of(CredentialCategory.SSH_TERMINAL, CredentialCategory.PKG_MANAGEMENT));

        // Without reassign -> throws conflict for PKG_MANAGEMENT, but allows SSH_TERMINAL
        CredentialResource.enforceExclusiveCategories(
                Set.of(CredentialCategory.SSH_TERMINAL), List.of(existing), null, false);

        assertThrows(
                IllegalStateException.class,
                () ->
                        CredentialResource.enforceExclusiveCategories(
                                Set.of(CredentialCategory.PKG_MANAGEMENT),
                                List.of(existing),
                                null,
                                false));

        // With reassign=true -> atomically removes PKG_MANAGEMENT from existing credential
        CredentialResource.enforceExclusiveCategories(
                Set.of(CredentialCategory.PKG_MANAGEMENT), List.of(existing), null, true);
        assertFalse(existing.categories.contains(CredentialCategory.PKG_MANAGEMENT));
        assertTrue(existing.categories.contains(CredentialCategory.SSH_TERMINAL));
    }
}

