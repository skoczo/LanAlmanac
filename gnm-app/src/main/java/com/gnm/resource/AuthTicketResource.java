package com.gnm.resource;

import com.gnm.auth.AuthTicketService;
import com.gnm.dto.AuthTicket;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Set;

@Path("/api/auth/ticket")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"gnm-admin", "gnm-operator", "gnm-viewer"})
public class AuthTicketResource {

    private final AuthTicketService ticketService;
    private final SecurityIdentity securityIdentity;

    public static class TicketRequest {
        public String purpose;
        public String resourceId;
    }

    @Inject
    public AuthTicketResource(AuthTicketService ticketService, SecurityIdentity securityIdentity) {
        this.ticketService = ticketService;
        this.securityIdentity = securityIdentity;
    }

    @POST
    public Response createTicket(TicketRequest request) {
        if (request == null || request.purpose == null || !AuthTicket.VALID_PURPOSES.contains(request.purpose)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid or missing ticket purpose"))
                    .build();
        }

        if (securityIdentity == null || securityIdentity.isAnonymous()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Authentication required"))
                    .build();
        }

        Set<String> roles = securityIdentity.getRoles();
        boolean isOperatorOrAdmin = roles.contains("gnm-admin") || roles.contains("gnm-operator");

        if ((AuthTicket.PURPOSE_SSE_UPDATE.equals(request.purpose)
                        || AuthTicket.PURPOSE_WS_TERMINAL.equals(request.purpose))
                && !isOperatorOrAdmin) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Insufficient permissions for requested ticket purpose"))
                    .build();
        }

        if ((AuthTicket.PURPOSE_SSE_UPDATE.equals(request.purpose)
                        || AuthTicket.PURPOSE_WS_TERMINAL.equals(request.purpose))
                && (request.resourceId == null || request.resourceId.isBlank())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "resourceId is required for this ticket purpose"))
                    .build();
        }

        String username = securityIdentity.getPrincipal().getName();
        AuthTicket ticket = ticketService.issue(username, roles, request.purpose, request.resourceId);

        return Response.ok(Map.of(
                        "ticket", ticket.token(),
                        "expiresAt", ticket.expiresAt().toString()))
                .build();
    }
}
