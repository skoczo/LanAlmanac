package com.gnm.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gnm.auth.AuthTicketService;
import com.gnm.dto.AuthTicket;
import com.gnm.service.VaultEngine;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpdateResourceTest {

    private VaultEngine vaultEngine;
    private AuthTicketService ticketService;
    private SecurityIdentity securityIdentity;
    private UpdateResource updateResource;
    private SseEventSink sink;
    private Sse sse;
    private List<String> sentEvents;

    @BeforeEach
    void setUp() {
        vaultEngine = mock(VaultEngine.class);
        ticketService = new AuthTicketService();
        securityIdentity = mock(SecurityIdentity.class);
        when(securityIdentity.isAnonymous()).thenReturn(true);

        updateResource = new UpdateResource(vaultEngine, ticketService, securityIdentity);

        sink = mock(SseEventSink.class);
        sse = mock(Sse.class);
        sentEvents = new ArrayList<>();

        when(sse.newEvent(anyString())).thenAnswer(invocation -> {
            String data = invocation.getArgument(0, String.class);
            sentEvents.add(data);
            OutboundSseEvent event = mock(OutboundSseEvent.class);
            when(event.getData()).thenReturn(data);
            return event;
        });
    }

    @Test
    void testStreamUpdateWithoutTicketReturns401AndDoesNotTouchVault() {
        UUID deviceId = UUID.randomUUID();

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> updateResource.streamUpdate(deviceId, null, sink, sse));

        assertEquals(401, ex.getResponse().getStatus());
        verifyNoInteractions(vaultEngine);
        verify(sink).close();
    }

    @Test
    void testStreamUpdateWithInvalidOrMismatchedTicketReturns401() {
        UUID deviceId = UUID.randomUUID();
        UUID otherDeviceId = UUID.randomUUID();

        // Ticket for a different device
        AuthTicket otherDeviceTicket = ticketService.issue(
                "admin", Set.of("gnm-admin"), AuthTicket.PURPOSE_SSE_UPDATE, otherDeviceId.toString());

        WebApplicationException ex1 = assertThrows(
                WebApplicationException.class,
                () -> updateResource.streamUpdate(deviceId, otherDeviceTicket.token(), sink, sse));
        assertEquals(401, ex1.getResponse().getStatus());

        // Ticket for a different purpose (ws-terminal)
        AuthTicket wrongPurposeTicket = ticketService.issue(
                "admin", Set.of("gnm-admin"), AuthTicket.PURPOSE_WS_TERMINAL, deviceId.toString());

        WebApplicationException ex2 = assertThrows(
                WebApplicationException.class,
                () -> updateResource.streamUpdate(deviceId, wrongPurposeTicket.token(), sink, sse));
        assertEquals(401, ex2.getResponse().getStatus());

        // Expired ticket
        AuthTicket expiredTicket = ticketService.issue(
                "admin",
                Set.of("gnm-admin"),
                AuthTicket.PURPOSE_SSE_UPDATE,
                deviceId.toString(),
                Duration.ofMillis(-10));

        WebApplicationException ex3 = assertThrows(
                WebApplicationException.class,
                () -> updateResource.streamUpdate(deviceId, expiredTicket.token(), sink, sse));
        assertEquals(401, ex3.getResponse().getStatus());

        // Viewer role ticket must be rejected
        AuthTicket viewerTicket = ticketService.issue(
                "viewer", Set.of("gnm-viewer"), AuthTicket.PURPOSE_SSE_UPDATE, deviceId.toString());

        WebApplicationException ex4 = assertThrows(
                WebApplicationException.class,
                () -> updateResource.streamUpdate(deviceId, viewerTicket.token(), sink, sse));
        assertEquals(401, ex4.getResponse().getStatus());

        verifyNoInteractions(vaultEngine);
    }

    @Test
    void testStreamUpdateWithValidTicketSucceedsOnceAndRejectsReuse() {
        UUID deviceId = UUID.randomUUID();
        when(vaultEngine.isUnsealed()).thenReturn(false);

        AuthTicket validTicket = ticketService.issue(
                "operator", Set.of("gnm-operator"), AuthTicket.PURPOSE_SSE_UPDATE, deviceId.toString());

        // First call with valid ticket passes authentication and checks vault status
        updateResource.streamUpdate(deviceId, validTicket.token(), sink, sse);
        verify(vaultEngine).isUnsealed();
        assertTrue(sentEvents.stream().anyMatch(e -> e.contains("Vault is sealed")));

        // Second call with the same single-use ticket must fail with 401
        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> updateResource.streamUpdate(deviceId, validTicket.token(), sink, sse));
        assertEquals(401, ex.getResponse().getStatus());
    }

    @Test
    void testSseTicketAuthMechanismAuthenticatesValidTicketAndRejectsMissingOrReused() {
        UpdateResource.SseTicketAuthMechanism mechanism =
                new UpdateResource.SseTicketAuthMechanism(ticketService);
        UUID deviceId = UUID.randomUUID();

        RoutingContext ctxNoTicket = mockRoutingContext("/api/devices/" + deviceId + "/update", null);
        assertThrows(
                AuthenticationFailedException.class,
                () -> mechanism.authenticate(ctxNoTicket, null).await().indefinitely());

        AuthTicket ticket = ticketService.issue(
                "admin", Set.of("gnm-admin"), AuthTicket.PURPOSE_SSE_UPDATE, deviceId.toString());
        RoutingContext ctxValid =
                mockRoutingContext("/api/devices/" + deviceId + "/update", ticket.token());

        SecurityIdentity identity = mechanism.authenticate(ctxValid, null).await().indefinitely();
        assertNotNull(identity);
        assertEquals("admin", identity.getPrincipal().getName());
        assertTrue(identity.hasRole("gnm-admin"));

        // Reusing the same ticket via the HTTP mechanism must fail with 401
        assertThrows(
                AuthenticationFailedException.class,
                () -> mechanism.authenticate(ctxValid, null).await().indefinitely());
    }

    @Test
    void testExecuteUpdateRejectsUntrustedSshHostKeyBeforePasswordAuth() throws Exception {
        AtomicBoolean passwordAuthAttempted = new AtomicBoolean(false);
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        SimpleGeneratorHostKeyProvider keyProvider = new SimpleGeneratorHostKeyProvider();
        sshd.setKeyPairProvider(keyProvider);
        sshd.setPasswordAuthenticator((username, password, session) -> {
            passwordAuthAttempted.set(true);
            return true;
        });
        sshd.start();

        try {
            String serverFingerprint = KeyUtils.getFingerPrint(
                    BuiltinDigests.sha256,
                    keyProvider.loadKeys(null).iterator().next().getPublic());

            // 1. Known fingerprint, but sshHostKeyTrusted = false -> must reject before decrypting/authenticating
            UpdateResource.UpdateTarget untrustedTarget = new UpdateResource.UpdateTarget(
                    UUID.randomUUID(),
                    "127.0.0.1",
                    "root",
                    sshd.getPort(),
                    new byte[] {1, 2, 3},
                    new byte[] {4, 5, 6},
                    null,
                    serverFingerprint,
                    false);

            updateResource.executeUpdate(untrustedTarget, sink, sse);

            assertTrue(
                    sentEvents.stream().anyMatch(e -> e.contains("SSH host key is not trusted")),
                    "Expected untrusted SSH host key error, got: " + sentEvents);
            verify(vaultEngine, never()).decrypt(any(), any());
            assertEquals(false, passwordAuthAttempted.get());

            // 2. Mismatched fingerprint even with sshHostKeyTrusted = true -> must reject
            sentEvents.clear();
            UpdateResource.UpdateTarget mismatchTarget = new UpdateResource.UpdateTarget(
                    UUID.randomUUID(),
                    "127.0.0.1",
                    "root",
                    sshd.getPort(),
                    new byte[] {1, 2, 3},
                    new byte[] {4, 5, 6},
                    null,
                    "SHA256:0000000000000000000000000000000000000000000",
                    true);

            updateResource.executeUpdate(mismatchTarget, sink, sse);

            assertTrue(
                    sentEvents.stream().anyMatch(e -> e.contains("SSH host key mismatch")),
                    "Expected SSH host key mismatch error, got: " + sentEvents);
            verify(vaultEngine, never()).decrypt(any(), any());
            assertEquals(false, passwordAuthAttempted.get());
        } finally {
            sshd.stop(true);
        }
    }

    @Test
    void testExecuteUpdateWithTrustedHostKeyZeroizesDecryptedSecret() throws Exception {
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        SimpleGeneratorHostKeyProvider keyProvider = new SimpleGeneratorHostKeyProvider();
        sshd.setKeyPairProvider(keyProvider);
        sshd.setPasswordAuthenticator((username, password, session) -> "s3cr3t!".equals(password));
        sshd.setCommandFactory((channel, command) -> new org.apache.sshd.server.command.Command() {
            private java.io.OutputStream out;
            private org.apache.sshd.server.ExitCallback callback;

            @Override
            public void setInputStream(java.io.InputStream in) {}

            @Override
            public void setOutputStream(java.io.OutputStream out) {
                this.out = out;
            }

            @Override
            public void setErrorStream(java.io.OutputStream err) {}

            @Override
            public void setExitCallback(org.apache.sshd.server.ExitCallback callback) {
                this.callback = callback;
            }

            @Override
            public void start(org.apache.sshd.server.channel.ChannelSession channel, org.apache.sshd.server.Environment env)
                    throws java.io.IOException {
                out.write("Reading package lists...\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                callback.onExit(0);
            }

            @Override
            public void destroy(org.apache.sshd.server.channel.ChannelSession channel) {}
        });
        sshd.start();

        try {
            String serverFingerprint = KeyUtils.getFingerPrint(
                    BuiltinDigests.sha256,
                    keyProvider.loadKeys(null).iterator().next().getPublic());

            byte[] secretBuffer = "s3cr3t!".getBytes(StandardCharsets.UTF_8);
            when(vaultEngine.decrypt(any(), any())).thenReturn(secretBuffer);

            UpdateResource.UpdateTarget trustedTarget = new UpdateResource.UpdateTarget(
                    UUID.randomUUID(),
                    "127.0.0.1",
                    "root",
                    sshd.getPort(),
                    new byte[] {1, 2, 3},
                    new byte[] {4, 5, 6},
                    null,
                    serverFingerprint,
                    true);

            updateResource.executeUpdate(trustedTarget, sink, sse);

            assertTrue(
                    sentEvents.stream().anyMatch(e -> e.contains("Update Complete.")),
                    "Expected Update Complete event, got: " + sentEvents);
            for (byte b : secretBuffer) {
                assertEquals(0, b, "Decrypted secret byte array must be zeroed after use");
            }
        } finally {
            sshd.stop(true);
        }
    }

    private static RoutingContext mockRoutingContext(String path, String ticket) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest req = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(req);
        when(req.path()).thenReturn(path);
        when(req.getParam("ticket")).thenReturn(ticket);
        return ctx;
    }
}

