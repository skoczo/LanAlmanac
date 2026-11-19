package com.gnm.pkgmgmt.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gnm.model.Credential;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.CredentialCategory;
import com.gnm.model.enums.CredentialType;
import com.gnm.service.VaultEngine;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PkgWorkerClientTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String TRUSTED_FP = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU";

    private HttpServer mockServer;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.start();
        baseUrl = "http://127.0.0.1:" + mockServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    private static PhysicalDevice buildDevice(boolean trustedHostKey) {
        PhysicalDevice d = new PhysicalDevice();
        d.id = UUID.randomUUID();
        d.displayName = "debian-node-1";

        NetworkIdentity id = new NetworkIdentity();
        id.ipAddress = "10.0.0.15";
        id.current = true;
        d.identities = Set.of(id);

        Credential primary = new Credential();
        primary.id = UUID.randomUUID();
        primary.username = "lanalmanac";
        primary.credentialType = CredentialType.SSH_KEY;
        primary.encryptedPayload = new byte[] {10, 20};
        primary.noncePayload = new byte[] {30, 40};
        primary.categories = Set.of(CredentialCategory.PKG_MANAGEMENT);
        d.credentials = List.of(primary);

        NetworkService ssh = new NetworkService();
        ssh.id = UUID.randomUUID();
        ssh.serviceType = "SSH";
        ssh.port = 22;
        ssh.sshHostKey = TRUSTED_FP;
        ssh.sshHostKeyTrusted = trustedHostKey;
        d.services = Set.of(ssh);
        return d;
    }

    @Test
    void refusesToDecryptCredentialWhenSshHostKeyIsUntrusted() {
        VaultEngine vault = mock(VaultEngine.class);
        when(vault.isUnsealed()).thenReturn(true);

        PkgWorkerClient client =
                new PkgWorkerClient(
                        vault,
                        new CredentialResolver(),
                        new ObjectMapper(),
                        baseUrl,
                        SECRET,
                        (device, actualFp) -> {});

        PhysicalDevice untrustedDevice = buildDevice(false);

        PkgWorkerException ex =
                assertThrows(
                        PkgWorkerException.class, () -> client.probeDevice(untrustedDevice, Map.of()));
        assertEquals("SSH_HOST_KEY_NOT_TRUSTED", ex.errorCode());
        verify(vault, never()).decrypt(any(), any());
    }

    @Test
    void signsRequestAndTriggersHostKeyMismatchCallbackWithoutFallback() {
        VaultEngine vault = mock(VaultEngine.class);
        when(vault.isUnsealed()).thenReturn(true);
        when(vault.decrypt(any(), any()))
                .thenAnswer(inv -> "private-key-material".getBytes(StandardCharsets.UTF_8));

        AtomicReference<String> receivedSig = new AtomicReference<>();
        AtomicReference<String> receivedTs = new AtomicReference<>();
        AtomicInteger callCount = new AtomicInteger();

        mockServer.createContext(
                "/v1/probe",
                exchange -> {
                    callCount.incrementAndGet();
                    receivedSig.set(exchange.getRequestHeaders().getFirst("X-GNM-Signature"));
                    receivedTs.set(exchange.getRequestHeaders().getFirst("X-GNM-Timestamp"));
                    byte[] resp =
                            "{\"errorCode\":\"HOST_KEY_MISMATCH\",\"message\":\"key changed\",\"actualFingerprint\":\"SHA256:evilKey\"}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(502, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                });

        AtomicReference<String> mismatchReported = new AtomicReference<>();
        PhysicalDevice device = buildDevice(true);
        // Add a fallback credential to prove HOST_KEY_MISMATCH never retries with fallback!
        Credential fallback = new Credential();
        fallback.id = UUID.randomUUID();
        fallback.username = "backup";
        fallback.credentialType = CredentialType.PASSWORD;
        fallback.encryptedPayload = new byte[] {1};
        fallback.noncePayload = new byte[] {2};
        fallback.categories = Set.of(CredentialCategory.PKG_MANAGEMENT_FALLBACK);
        device.credentials = List.of(device.credentials.get(0), fallback);

        PkgWorkerClient client =
                new PkgWorkerClient(
                        vault,
                        new CredentialResolver(),
                        new ObjectMapper(),
                        baseUrl,
                        SECRET,
                        (dev, actualFp) -> mismatchReported.set(actualFp));

        PkgWorkerException ex =
                assertThrows(PkgWorkerException.class, () -> client.probeDevice(device, Map.of()));
        assertEquals("HOST_KEY_MISMATCH", ex.errorCode());
        assertEquals("SHA256:evilKey", mismatchReported.get());
        assertNotNull(receivedSig.get());
        assertNotNull(receivedTs.get());
        assertEquals(
                1,
                callCount.get(),
                "HOST_KEY_MISMATCH must never trigger fallback credential retry");
    }

    @Test
    void retriesOnceWithFallbackCredentialOnAuthFailed() {
        VaultEngine vault = mock(VaultEngine.class);
        when(vault.isUnsealed()).thenReturn(true);
        when(vault.decrypt(any(), any()))
                .thenAnswer(inv -> "secret-payload".getBytes(StandardCharsets.UTF_8));

        AtomicInteger callCount = new AtomicInteger();
        mockServer.createContext(
                "/v1/probe",
                exchange -> {
                    int attempt = callCount.incrementAndGet();
                    byte[] resp;
                    int status;
                    if (attempt == 1) {
                        status = 502;
                        resp =
                                "{\"errorCode\":\"AUTH_FAILED\",\"message\":\"primary key rejected\"}"
                                        .getBytes(StandardCharsets.UTF_8);
                    } else {
                        status = 200;
                        resp =
                                "{\"osFacts\":{\"os.id\":\"debian\",\"os.version_major\":\"12\"}}"
                                        .getBytes(StandardCharsets.UTF_8);
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                });

        PhysicalDevice device = buildDevice(true);
        Credential fallback = new Credential();
        fallback.id = UUID.randomUUID();
        fallback.username = "backup-admin";
        fallback.credentialType = CredentialType.PASSWORD;
        fallback.encryptedPayload = new byte[] {5};
        fallback.noncePayload = new byte[] {6};
        fallback.categories = Set.of(CredentialCategory.PKG_MANAGEMENT_FALLBACK);
        device.credentials = List.of(device.credentials.get(0), fallback);

        PkgWorkerClient client =
                new PkgWorkerClient(
                        vault,
                        new CredentialResolver(),
                        new ObjectMapper(),
                        baseUrl,
                        SECRET,
                        (dev, actualFp) -> {});

        var res = client.probeDevice(device, Map.of());
        assertEquals(2, callCount.get());
        assertTrue(res.usedFallback());
        assertEquals(fallback.id, res.credentialId());
        assertEquals("debian", res.response().osFacts().get("os.id"));
        verify(vault, times(2)).decrypt(any(), any());
    }
}

