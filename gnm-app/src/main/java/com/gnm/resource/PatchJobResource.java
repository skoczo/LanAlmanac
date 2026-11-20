package com.gnm.resource;

import com.gnm.auth.AuthTicketService;
import com.gnm.dto.AuthTicket;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.PatchJobDetailDto;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.PatchJobDto;
import com.gnm.pkgmgmt.dto.PackageManagementDtos.PatchJobLogDto;
import com.gnm.pkgmgmt.patching.PatchJob;
import com.gnm.pkgmgmt.patching.PatchJobLog;
import com.gnm.pkgmgmt.patching.PatchJobLogBroker;
import com.gnm.pkgmgmt.patching.PatchJobService;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"gnm-admin", "gnm-operator", "gnm-viewer"})
public class PatchJobResource {

    private static final String SSE_PATCH_JOB_TICKET_ATTR = "gnm.sse.patch.ticket";

    private final PatchJobService patchJobService;
    private final PatchJobLogBroker logBroker;
    private final AuthTicketService ticketService;
    private final SecurityIdentity securityIdentity;

    @Inject
    public PatchJobResource(
            PatchJobService patchJobService,
            PatchJobLogBroker logBroker,
            AuthTicketService ticketService,
            SecurityIdentity securityIdentity) {
        this.patchJobService = patchJobService;
        this.logBroker = logBroker;
        this.ticketService = ticketService;
        this.securityIdentity = securityIdentity;
    }

    @GET
    @Path("/patch-jobs/{jobId}")
    @Transactional
    public Response getPatchJob(
            @PathParam("jobId") UUID jobId, @QueryParam("afterSeq") long afterSeq) {
        PatchJob job = PatchJob.findById(jobId);
        if (job == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        List<PatchJobLog> logs =
                PatchJobLog.list(
                        "jobId = ?1 and seq > ?2 order by seq asc", jobId, afterSeq);
        return Response.ok(
                        new PatchJobDetailDto(
                                PatchJobDto.fromEntity(job),
                                logs.stream().map(PatchJobLogDto::fromEntity).toList()))
                .build();
    }

    @POST
    @Path("/patch-jobs/{jobId}/cancel")
    @RolesAllowed({"gnm-admin", "gnm-operator"})
    public Response cancelPatchJob(@PathParam("jobId") UUID jobId) {
        boolean cancelled = patchJobService.cancelJob(jobId);
        return Response.ok(Map.of("jobId", jobId, "cancelled", cancelled)).build();
    }

    @GET
    @Path("/patch-jobs/{jobId}/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RolesAllowed({"gnm-admin", "gnm-operator"})
    @Transactional
    public void streamPatchJobLogs(
            @PathParam("jobId") UUID jobId,
            @QueryParam("ticket") String ticket,
            @QueryParam("fromSeq") Long fromSeq,
            @HeaderParam("Last-Event-ID") String lastEventIdHeader,
            @Context SseEventSink sink,
            @Context Sse sse) {
        if (!isTicketValid(jobId, ticket)) {
            if (sink != null && !sink.isClosed()) {
                sink.close();
            }
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        }

        PatchJob job = PatchJob.findById(jobId);
        if (job == null) {
            sink.send(sse.newEvent("Error: Patch job not found."));
            sink.close();
            return;
        }

        long afterSeq = resolveAfterSeq(fromSeq, lastEventIdHeader);
        List<PatchJobLog> dbHistory =
                PatchJobLog.list(
                        "jobId = ?1 and seq > ?2 order by seq asc", jobId, afterSeq);

        Thread.startVirtualThread(
                () -> {
                    Consumer<PatchJobLog> sub = null;
                    try {
                        sub =
                                logBroker.subscribeWithReplay(
                                        jobId,
                                        afterSeq,
                                        dbHistory,
                                        entry -> {
                                            if (!sink.isClosed()) {
                                                String json =
                                                        "{\"seq\":"
                                                                + entry.seq
                                                                + ",\"stream\":\""
                                                                + entry.stream
                                                                + "\",\"line\":\""
                                                                + escapeJson(entry.line)
                                                                + "\"}";
                                                sink.send(
                                                        sse.newEventBuilder()
                                                                .id(String.valueOf(entry.seq))
                                                                .data(json)
                                                                .build());
                                            }
                                        });
                        long deadline =
                                System.currentTimeMillis() + Duration.ofMinutes(35).toMillis();
                        while (!sink.isClosed()
                                && !logBroker.isCompleted(jobId)
                                && System.currentTimeMillis() < deadline) {
                            Thread.sleep(250);
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        logBroker.unsubscribe(jobId, sub);
                        if (!sink.isClosed()) {
                            sink.close();
                        }
                    }
                });
    }

    private boolean isTicketValid(UUID jobId, String ticket) {
        if (jobId == null) {
            return false;
        }
        String resourceId = jobId.toString();
        if (securityIdentity != null && !securityIdentity.isAnonymous()) {
            AuthTicket preConsumed = securityIdentity.getAttribute(SSE_PATCH_JOB_TICKET_ATTR);
            if (preConsumed != null
                    && AuthTicket.PURPOSE_SSE_PATCH_JOB.equals(preConsumed.purpose())
                    && resourceId.equals(preConsumed.resourceId())
                    && hasOperatorOrAdminRole(preConsumed.roles())) {
                return true;
            }
        }
        if (ticketService == null) {
            return false;
        }
        Optional<AuthTicket> consumed =
                ticketService.consume(ticket, AuthTicket.PURPOSE_SSE_PATCH_JOB, resourceId);
        return consumed.isPresent() && hasOperatorOrAdminRole(consumed.get().roles());
    }

    private static boolean hasOperatorOrAdminRole(Set<String> roles) {
        return roles != null && (roles.contains("gnm-admin") || roles.contains("gnm-operator"));
    }

    private static long resolveAfterSeq(Long fromSeq, String lastEventIdHeader) {
        if (lastEventIdHeader != null && !lastEventIdHeader.isBlank()) {
            try {
                return Long.parseLong(lastEventIdHeader.trim());
            } catch (NumberFormatException ignored) {
                // Fall back to fromSeq
            }
        }
        return fromSeq != null && fromSeq > 0 ? fromSeq : 0L;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    @ApplicationScoped
    @Priority(Priorities.AUTHENTICATION - 9)
    public static class SsePatchJobTicketAuthMechanism implements HttpAuthenticationMechanism {

        private static final Pattern STREAM_PATH_PATTERN =
                Pattern.compile("^/api/patch-jobs/([^/]+)/stream$");

        private final AuthTicketService ticketService;

        @Inject
        public SsePatchJobTicketAuthMechanism(AuthTicketService ticketService) {
            this.ticketService = ticketService;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(
                RoutingContext context, IdentityProviderManager identityProviderManager) {
            Matcher matcher = STREAM_PATH_PATTERN.matcher(context.request().path());
            if (!matcher.matches()) {
                return Uni.createFrom().nullItem();
            }

            String jobId = matcher.group(1);
            String ticket = context.request().getParam("ticket");
            Optional<AuthTicket> consumed =
                    ticketService.consume(ticket, AuthTicket.PURPOSE_SSE_PATCH_JOB, jobId);
            if (consumed.isEmpty() || !hasOperatorOrAdminRole(consumed.get().roles())) {
                return Uni.createFrom()
                        .failure(
                                new AuthenticationFailedException(
                                        "Invalid or missing SSE patch-job ticket"));
            }

            AuthTicket authTicket = consumed.get();
            SecurityIdentity identity =
                    QuarkusSecurityIdentity.builder()
                            .setPrincipal(new QuarkusPrincipal(authTicket.username()))
                            .addRoles(authTicket.roles())
                            .addAttribute(SSE_PATCH_JOB_TICKET_ATTR, authTicket)
                            .build();
            return Uni.createFrom().item(identity);
        }

        @Override
        public Uni<ChallengeData> getChallenge(RoutingContext context) {
            Matcher matcher = STREAM_PATH_PATTERN.matcher(context.request().path());
            if (!matcher.matches()) {
                return Uni.createFrom().nullItem();
            }
            return Uni.createFrom().item(new ChallengeData(401, null, null));
        }

        @Override
        public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
            return Uni.createFrom()
                    .item(
                            new HttpCredentialTransport(
                                    HttpCredentialTransport.Type.OTHER_HEADER,
                                    "X-GNM-SSE-Patch-Ticket"));
        }
    }
}

