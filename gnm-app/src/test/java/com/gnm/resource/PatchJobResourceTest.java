package com.gnm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gnm.auth.AuthTicketService;
import com.gnm.dto.AuthTicket;
import com.gnm.pkgmgmt.patching.PatchJobLogBroker;
import com.gnm.pkgmgmt.patching.PatchJobService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatchJobResourceTest {

    @Test
    void enforcesRolesAllowedAndRejectsMissingOrMismatchedSseTicket() {
        RolesAllowed classRoles = PatchJobResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(classRoles);
        assertEquals(
                Set.of("gnm-admin", "gnm-operator", "gnm-viewer"),
                Set.copyOf(Arrays.asList(classRoles.value())));

        for (String methodName : List.of("startPatchJob", "cancelPatchJob")) {
            Method m =
                    Arrays.stream(PatchJobResource.class.getDeclaredMethods())
                            .filter(method -> method.getName().equals(methodName))
                            .findFirst()
                            .orElseThrow();
            RolesAllowed methodRoles = m.getAnnotation(RolesAllowed.class);
            assertNotNull(methodRoles);
            assertEquals(
                    Set.of("gnm-admin", "gnm-operator"),
                    Set.copyOf(Arrays.asList(methodRoles.value())));
        }

        AuthTicketService ticketService = new AuthTicketService();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);

        PatchJobResource resource =
                new PatchJobResource(
                        mock(PatchJobService.class),
                        new PatchJobLogBroker(),
                        ticketService,
                        identity);

        SseEventSink sink = mock(SseEventSink.class);
        Sse sse = mock(Sse.class);
        UUID jobId = UUID.randomUUID();

        // Missing ticket -> 401
        WebApplicationException ex1 =
                assertThrows(
                        WebApplicationException.class,
                        () -> resource.streamPatchJobLogs(jobId, null, null, null, sink, sse));
        assertEquals(401, ex1.getResponse().getStatus());
        verify(sink).close();

        // Ticket for another jobId -> 401
        AuthTicket wrongJobTicket =
                ticketService.issue(
                        "operator",
                        Set.of("gnm-operator"),
                        AuthTicket.PURPOSE_SSE_PATCH_JOB,
                        UUID.randomUUID().toString());
        SseEventSink sink2 = mock(SseEventSink.class);
        WebApplicationException ex2 =
                assertThrows(
                        WebApplicationException.class,
                        () ->
                                resource.streamPatchJobLogs(
                                        jobId, wrongJobTicket.token(), null, null, sink2, sse));
        assertEquals(401, ex2.getResponse().getStatus());
    }
}

