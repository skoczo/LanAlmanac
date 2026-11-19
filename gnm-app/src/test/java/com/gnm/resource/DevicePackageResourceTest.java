package com.gnm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.annotation.security.RolesAllowed;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DevicePackageResourceTest {

    @Test
    void enforcesExplicitRolesAllowedMatrixOnClassAndMutatingEndpoints() {
        RolesAllowed classRoles = DevicePackageResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(classRoles, "DevicePackageResource must have class-level @RolesAllowed");
        assertEquals(
                Set.of("gnm-admin", "gnm-operator", "gnm-viewer"),
                Set.copyOf(Arrays.asList(classRoles.value())));

        for (String methodName :
                List.of(
                        "updateOsProfileAssignment",
                        "detectOsProfile",
                        "triggerInventoryScan",
                        "triggerVulnerabilityScan",
                        "triggerUpdateCheck",
                        "updatePackageSchedule")) {
            Method m =
                    Arrays.stream(DevicePackageResource.class.getDeclaredMethods())
                            .filter(method -> method.getName().equals(methodName))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "Missing expected endpoint method "
                                                            + methodName));
            RolesAllowed methodRoles = m.getAnnotation(RolesAllowed.class);
            assertNotNull(
                    methodRoles, "Mutating method " + methodName + " must restrict @RolesAllowed");
            assertEquals(
                    Set.of("gnm-admin", "gnm-operator"),
                    Set.copyOf(Arrays.asList(methodRoles.value())));
        }
    }
}

