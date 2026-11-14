package com.gnm.resource;

import com.gnm.auth.AuthTicketService;
import com.gnm.dto.AuthTicket;
import com.gnm.model.Credential;
import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.CredentialType;
import com.gnm.service.VaultEngine;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
import jakarta.ws.rs.GET;
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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.jboss.logging.Logger;

@Path("/api/devices")
@RolesAllowed({"gnm-admin", "gnm-operator"})
public class UpdateResource {

    private static final Logger log = Logger.getLogger(UpdateResource.class);
    private static final String SSE_TICKET_ATTR = "gnm.sse.ticket";
    private static final long MAX_SESSION_DURATION_MS = Duration.ofMinutes(15).toMillis();

    private final VaultEngine vaultEngine;
    private final AuthTicketService ticketService;
    private final SecurityIdentity securityIdentity;

    record UpdateTarget(
            UUID deviceId,
            String ipAddress,
            String username,
            int port,
            byte[] encryptedPayload,
            byte[] noncePayload,
            UUID serviceId,
            String sshHostKey,
            boolean sshHostKeyTrusted) {}

    @Inject
    public UpdateResource(
            VaultEngine vaultEngine,
            AuthTicketService ticketService,
            SecurityIdentity securityIdentity) {
        this.vaultEngine = vaultEngine;
        this.ticketService = ticketService;
        this.securityIdentity = securityIdentity;
    }

    @GET
    @Path("/{id}/update")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Transactional
    public void streamUpdate(
            @PathParam("id") UUID id,
            @QueryParam("ticket") String ticket,
            @Context SseEventSink sink,
            @Context Sse sse) {
        if (!isTicketValid(id, ticket)) {
            if (sink != null && !sink.isClosed()) {
                sink.close();
            }
            throw new WebApplicationException(Response.Status.UNAUTHORIZED);
        }

        if (!vaultEngine.isUnsealed()) {
            sink.send(sse.newEvent("Error: Vault is sealed."));
            sink.close();
            return;
        }

        PhysicalDevice device = PhysicalDevice.findById(id);
        if (device == null) {
            sink.send(sse.newEvent("Error: Device not found."));
            sink.close();
            return;
        }

        String ipAddress = device.identities.stream()
                .filter(i -> i.current)
                .map(i -> i.ipAddress)
                .findFirst()
                .orElse(null);

        if (ipAddress == null) {
            sink.send(sse.newEvent("Error: Device has no active IP."));
            sink.close();
            return;
        }

        Credential cred = device.credentials.stream()
                .filter(c -> c.credentialType == CredentialType.PASSWORD
                        || c.credentialType == CredentialType.SSH_KEY)
                .findFirst()
                .orElse(null);

        if (cred == null) {
            sink.send(sse.newEvent("Error: No SSH credentials found for device."));
            sink.close();
            return;
        }

        NetworkService sshService = device.services.stream()
                .filter(s -> "SSH".equalsIgnoreCase(s.serviceType))
                .findFirst()
                .orElse(null);

        UpdateTarget target = new UpdateTarget(
                id,
                ipAddress,
                cred.username,
                cred.port != null ? cred.port : 22,
                Arrays.copyOf(cred.encryptedPayload, cred.encryptedPayload.length),
                Arrays.copyOf(cred.noncePayload, cred.noncePayload.length),
                sshService != null ? sshService.id : null,
                sshService != null ? sshService.sshHostKey : null,
                sshService != null && Boolean.TRUE.equals(sshService.sshHostKeyTrusted));

        Thread.startVirtualThread(() -> executeUpdate(target, sink, sse));
    }

    private boolean isTicketValid(UUID id, String ticket) {
        if (id == null) {
            return false;
        }
        String resourceId = id.toString();
        if (securityIdentity != null && !securityIdentity.isAnonymous()) {
            AuthTicket preConsumed = securityIdentity.getAttribute(SSE_TICKET_ATTR);
            if (preConsumed != null
                    && AuthTicket.PURPOSE_SSE_UPDATE.equals(preConsumed.purpose())
                    && resourceId.equals(preConsumed.resourceId())
                    && hasOperatorOrAdminRole(preConsumed.roles())) {
                return true;
            }
        }
        if (ticketService == null) {
            return false;
        }
        Optional<AuthTicket> consumed =
                ticketService.consume(ticket, AuthTicket.PURPOSE_SSE_UPDATE, resourceId);
        return consumed.isPresent() && hasOperatorOrAdminRole(consumed.get().roles());
    }

    private static boolean hasOperatorOrAdminRole(Set<String> roles) {
        return roles != null && (roles.contains("gnm-admin") || roles.contains("gnm-operator"));
    }

    void executeUpdate(UpdateTarget target, SseEventSink sink, Sse sse) {
        long deadlineMs = System.currentTimeMillis() + MAX_SESSION_DURATION_MS;
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
            String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
            if (target.sshHostKey() == null || target.sshHostKey().isBlank()) {
                if (target.serviceId() != null) {
                    QuarkusTransaction.requiringNew().run(() -> {
                        NetworkService s = NetworkService.findById(target.serviceId());
                        if (s != null) {
                            s.sshHostKey = fingerprint;
                            s.sshHostKeyTrusted = false;
                            s.persist();
                        }
                    });
                }
                sink.send(sse.newEvent(
                        "Error: SSH host key is not trusted. Fingerprint: " + fingerprint));
                return false;
            }
            if (!target.sshHostKey().equals(fingerprint)) {
                sink.send(sse.newEvent("Error: SSH host key mismatch detected."));
                return false;
            }
            if (!target.sshHostKeyTrusted()) {
                sink.send(sse.newEvent("Error: SSH host key is not trusted."));
                return false;
            }
            return true;
        });
        client.start();

        byte[] decryptedBytes = null;
        char[] passwordChars = null;
        try {
            ClientSession session = client.connect(target.username(), target.ipAddress(), target.port())
                    .verify(10000)
                    .getSession();

            Set<ClientSession.ClientSessionEvent> kexEvents = session.waitFor(
                    EnumSet.of(
                            ClientSession.ClientSessionEvent.WAIT_AUTH,
                            ClientSession.ClientSessionEvent.CLOSED),
                    10000);
            if (kexEvents.contains(ClientSession.ClientSessionEvent.CLOSED)
                    || !kexEvents.contains(ClientSession.ClientSessionEvent.WAIT_AUTH)) {
                return;
            }

            decryptedBytes = vaultEngine.decrypt(target.encryptedPayload(), target.noncePayload());
            CharBuffer charBuffer = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(decryptedBytes));
            passwordChars = new char[charBuffer.remaining()];
            charBuffer.get(passwordChars);
            if ( charBuffer.hasArray() ) {
                Arrays.fill(charBuffer.array(), '\0');
            }
            Arrays.fill(decryptedBytes, (byte) 0);

            session.addPasswordIdentity(new String(passwordChars));
            Arrays.fill(passwordChars, '\0');
            session.auth().verify(10000);

            String command = "sudo apt-get update && sudo apt-get upgrade -y";
            try (ChannelExec channel = session.createExecChannel(command)) {
                channel.open().verify(5000);

                InputStream in = channel.getInvertedOut();
                InputStream err = channel.getInvertedErr();

                byte[] buffer = new byte[1024];
                int i;
                while ((i = in.read(buffer)) != -1) {
                    if (System.currentTimeMillis() > deadlineMs) {
                        sink.send(sse.newEvent("Error: Update session timed out."));
                        break;
                    }
                    String out = new String(buffer, 0, i, StandardCharsets.UTF_8);
                    sink.send(sse.newEvent(out.replace("\n", "\\n")));
                }

                while ((i = err.read(buffer)) != -1) {
                    if (System.currentTimeMillis() > deadlineMs) {
                        break;
                    }
                    String out = new String(buffer, 0, i, StandardCharsets.UTF_8);
                    sink.send(sse.newEvent("ERROR: " + out.replace("\n", "\\n")));
                }

                long remainingMs = Math.max(1000L, deadlineMs - System.currentTimeMillis());
                Set<ClientChannelEvent> events =
                        channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), remainingMs);
                if (events.contains(ClientChannelEvent.TIMEOUT)) {
                    sink.send(sse.newEvent("Error: Update session timed out."));
                } else {
                    sink.send(sse.newEvent("Update Complete."));
                }
            }
            session.close(false);
        } catch (Exception e) {
            log.error("Update failed", e);
            sink.send(sse.newEvent("Update Failed: " + e.getMessage()));
        } finally {
            if (decryptedBytes != null) {
                Arrays.fill(decryptedBytes, (byte) 0);
            }
            if (passwordChars != null) {
                Arrays.fill(passwordChars, '\0');
            }
            sink.close();
            try {
                client.stop();
            } catch (Exception stopEx) {
                log.debug("Error stopping SSH client", stopEx);
            }
        }
    }

    @ApplicationScoped
    @Priority(Priorities.AUTHENTICATION - 10)
    public static class SseTicketAuthMechanism implements HttpAuthenticationMechanism {

        private static final Pattern UPDATE_PATH_PATTERN =
                Pattern.compile("^/api/devices/([^/]+)/update$");

        private final AuthTicketService ticketService;

        @Inject
        public SseTicketAuthMechanism(AuthTicketService ticketService) {
            this.ticketService = ticketService;
        }

        @Override
        public Uni<SecurityIdentity> authenticate(
                RoutingContext context, IdentityProviderManager identityProviderManager) {
            Matcher matcher = UPDATE_PATH_PATTERN.matcher(context.request().path());
            if (!matcher.matches()) {
                return Uni.createFrom().nullItem();
            }

            String deviceId = matcher.group(1);
            String ticket = context.request().getParam("ticket");
            Optional<AuthTicket> consumed =
                    ticketService.consume(ticket, AuthTicket.PURPOSE_SSE_UPDATE, deviceId);
            if (consumed.isEmpty() || !hasOperatorOrAdminRole(consumed.get().roles())) {
                return Uni.createFrom()
                        .failure(new AuthenticationFailedException("Invalid or missing SSE ticket"));
            }

            AuthTicket authTicket = consumed.get();
            SecurityIdentity identity = QuarkusSecurityIdentity.builder()
                    .setPrincipal(new QuarkusPrincipal(authTicket.username()))
                    .addRoles(authTicket.roles())
                    .addAttribute(SSE_TICKET_ATTR, authTicket)
                    .build();
            return Uni.createFrom().item(identity);
        }

        @Override
        public Uni<ChallengeData> getChallenge(RoutingContext context) {
            Matcher matcher = UPDATE_PATH_PATTERN.matcher(context.request().path());
            if (!matcher.matches()) {
                return Uni.createFrom().nullItem();
            }
            return Uni.createFrom().item(new ChallengeData(401, null, null));
        }

        @Override
        public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
            return Uni.createFrom()
                    .item(new HttpCredentialTransport(
                            HttpCredentialTransport.Type.OTHER_HEADER, "X-GNM-SSE-Ticket"));
        }
    }
}
