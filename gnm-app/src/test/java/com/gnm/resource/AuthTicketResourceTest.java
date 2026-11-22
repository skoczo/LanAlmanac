package com.gnm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gnm.auth.AuthTicketService;
import com.gnm.dto.AuthTicket;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthTicketResourceTest {

    private AuthTicketService ticketService;
    private SecurityIdentity securityIdentity;
    private AuthTicketResource resource;

    @BeforeEach
    void setUp() {
        ticketService = new AuthTicketService();
        securityIdentity = mock(SecurityIdentity.class);
        resource = new AuthTicketResource(ticketService, securityIdentity);
    }

    @Test
    void testCreateTicketInvalidOrMissingPurposeReturns400() {
        Response r1 = resource.createTicket(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r1.getStatus());

        AuthTicketResource.TicketRequest req = new AuthTicketResource.TicketRequest();
        req.purpose = "invalid-purpose";
        Response r2 = resource.createTicket(req);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r2.getStatus());
    }

    @Test
    void testCreateTicketAnonymousReturns401() {
        AuthTicketResource.TicketRequest req = new AuthTicketResource.TicketRequest();
        req.purpose = AuthTicket.PURPOSE_SSE_UPDATE;
        req.resourceId = "device-123";

        when(securityIdentity.isAnonymous()).thenReturn(true);

        Response res = resource.createTicket(req);
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), res.getStatus());
    }

    @Test
    void testCreateTicketInsufficientRolesReturns403() {
        AuthTicketResource.TicketRequest req = new AuthTicketResource.TicketRequest();
        req.purpose = AuthTicket.PURPOSE_SSE_UPDATE;
        req.resourceId = "device-123";

        when(securityIdentity.isAnonymous()).thenReturn(false);
        when(securityIdentity.getRoles()).thenReturn(Set.of("gnm-viewer"));

        Response res = resource.createTicket(req);
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), res.getStatus());
    }

    @Test
    void testCreateTicketMissingResourceIdReturns400() {
        AuthTicketResource.TicketRequest req = new AuthTicketResource.TicketRequest();
        req.purpose = AuthTicket.PURPOSE_WS_TERMINAL;
        req.resourceId = "";

        when(securityIdentity.isAnonymous()).thenReturn(false);
        when(securityIdentity.getRoles()).thenReturn(Set.of("gnm-admin"));

        Response res = resource.createTicket(req);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), res.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCreateTicketSuccessReturns200() {
        AuthTicketResource.TicketRequest req = new AuthTicketResource.TicketRequest();
        req.purpose = AuthTicket.PURPOSE_SSE_UPDATE;
        req.resourceId = "device-123";

        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("admin");
        when(securityIdentity.isAnonymous()).thenReturn(false);
        when(securityIdentity.getRoles()).thenReturn(Set.of("gnm-admin"));
        when(securityIdentity.getPrincipal()).thenReturn(principal);

        Response res = resource.createTicket(req);
        assertEquals(Response.Status.OK.getStatusCode(), res.getStatus());
        Map<String, Object> body = (Map<String, Object>) res.getEntity();
        assertNotNull(body.get("ticket"));
        assertNotNull(body.get("expiresAt"));
    }
}

