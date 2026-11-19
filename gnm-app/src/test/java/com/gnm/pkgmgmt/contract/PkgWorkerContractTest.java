package com.gnm.pkgmgmt.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gnm.model.Credential;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.CredentialCategory;
import com.gnm.model.enums.CredentialType;
import com.gnm.pkgmgmt.client.CredentialResolver;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.PkgWorkerHmacSigner;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.HealthResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventoryResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventorySpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.LockSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchExecSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchStreamFrame;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PrivilegeSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.SshFingerprintResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckSpec;
import com.gnm.service.VaultEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("contract")
class PkgWorkerContractTest {

    private static final String SECRET = "contract-test-hmac-secret-32bytes!";
    private static final String TRUSTED_FP = "SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU";

    private HttpServer workerServer;
    private PkgWorkerClient client;

    @BeforeEach
    void setUp() throws Exception {
        workerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        workerServer.createContext(
                "/v1/health",
                exchange -> {
                    if (!verifySignature(exchange, "GET", "/v1/health")) return;
                    respondJson(
                            exchange,
                            200,
                            "{\"status\":\"UP\",\"contractVersion\":\"v1\",\"workerVersion\":\"0.1.0\",\"implementation\":\"go\",\"supportedTransports\":[\"SSH\"],\"supportedParsers\":[\"DELIMITED\",\"KV_BLOCKS\",\"JSON_MAP\",\"REGEX\"],\"supportedElevationModes\":[\"NONE\",\"SUDO_NOPASSWD\"]}");
                });

        workerServer.createContext(
                "/v1/ssh/fingerprint",
                exchange -> {
                    if (!verifySignature(exchange, "POST", "/v1/ssh/fingerprint")) return;
                    respondJson(
                            exchange,
                            200,
                            "{\"fingerprintSha256\":\""
                                    + TRUSTED_FP
                                    + "\",\"keyAlgorithm\":\"ssh-ed25519\"}");
                });

        workerServer.createContext(
                "/v1/inventory",
                exchange -> {
                    if (!verifySignature(exchange, "POST", "/v1/inventory")) return;
                    respondJson(
                            exchange,
                            200,
                            "{\"packages\":[{\"name\":\"openssl\",\"version\":\"3.0.13\",\"arch\":\"amd64\",\"sourceName\":\"openssl\",\"sourceVersion\":\"3.0.13\",\"purl\":\"pkg:deb/debian/openssl@3.0.13\"}],\"totalParsed\":1,\"skippedLines\":0,\"sampleSkippedLines\":[],\"durationMs\":12}");
                });

        workerServer.createContext(
                "/v1/updates/check",
                exchange -> {
                    if (!verifySignature(exchange, "POST", "/v1/updates/check")) return;
                    respondJson(
                            exchange,
                            200,
                            "{\"updates\":[{\"packageName\":\"curl\",\"arch\":\"amd64\",\"currentVersion\":\"8.5.0-1\",\"candidateVersion\":\"8.5.0-2\",\"isSecurityUpdate\":true}],\"exitCode\":100,\"retriesUsed\":0,\"durationMs\":35}");
                });

        workerServer.createContext(
                "/v1/patch/execute",
                exchange -> {
                    if (!verifySignature(exchange, "POST", "/v1/patch/execute")) return;
                    String ndjson =
                            "{\"type\":\"PHASE\",\"seq\":1,\"ts\":\"2026-11-19T12:00:00Z\",\"phase\":\"REFRESH\"}\n"
                                    + "{\"type\":\"LOG\",\"seq\":2,\"ts\":\"2026-11-19T12:00:01Z\",\"stream\":\"STDOUT\",\"line\":\"Hit:1 http://deb.debian.org/debian bookworm InRelease\"}\n"
                                    + "{\"type\":\"RESULT\",\"seq\":3,\"ts\":\"2026-11-19T12:00:02Z\",\"status\":\"SUCCEEDED\",\"exitCode\":0,\"rebootRequired\":false}\n";
                    byte[] bytes = ndjson.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                });

        workerServer.start();
        String baseUrl = "http://127.0.0.1:" + workerServer.getAddress().getPort();

        VaultEngine vault = mock(VaultEngine.class);
        when(vault.isUnsealed()).thenReturn(true);
        when(vault.decrypt(any(), any()))
                .thenAnswer(inv -> "ssh-key".getBytes(StandardCharsets.UTF_8));

        client =
                new PkgWorkerClient(
                        vault,
                        new CredentialResolver(),
                        new ObjectMapper(),
                        baseUrl,
                        SECRET,
                        (dev, fp) -> {});
    }

    @AfterEach
    void tearDown() {
        if (workerServer != null) {
            workerServer.stop(0);
        }
    }

    private boolean verifySignature(HttpExchange exchange, String method, String path)
            throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String ts = exchange.getRequestHeaders().getFirst(PkgWorkerHmacSigner.HEADER_TIMESTAMP);
        String sig = exchange.getRequestHeaders().getFirst(PkgWorkerHmacSigner.HEADER_SIGNATURE);
        String expected =
                PkgWorkerHmacSigner.computeSignature(
                        SECRET.getBytes(StandardCharsets.UTF_8), method, path, ts, body);
        if (!expected.equals(sig)) {
            respondJson(exchange, 401, "{\"errorCode\":\"UNAUTHORIZED\",\"message\":\"bad hmac\"}");
            return false;
        }
        return true;
    }

    private static void respondJson(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static PhysicalDevice buildDevice() {
        PhysicalDevice d = new PhysicalDevice();
        d.id = UUID.randomUUID();
        d.displayName = "lab-debian";

        NetworkIdentity id = new NetworkIdentity();
        id.ipAddress = "10.10.0.5";
        id.current = true;
        d.identities = Set.of(id);

        Credential cred = new Credential();
        cred.id = UUID.randomUUID();
        cred.username = "lanalmanac";
        cred.credentialType = CredentialType.SSH_KEY;
        cred.encryptedPayload = new byte[] {1, 2};
        cred.noncePayload = new byte[] {3, 4};
        cred.categories = Set.of(CredentialCategory.PKG_MANAGEMENT);
        d.credentials = List.of(cred);

        NetworkService ssh = new NetworkService();
        ssh.id = UUID.randomUUID();
        ssh.serviceType = "SSH";
        ssh.port = 22;
        ssh.sshHostKey = TRUSTED_FP;
        ssh.sshHostKeyTrusted = true;
        d.services = Set.of(ssh);
        return d;
    }

    @Test
    void verifiesV1HealthFingerprintInventoryUpdatesAndNdjsonPatchStreamContract() {
        HealthResponse health = client.getHealth();
        assertEquals("UP", health.status());
        assertEquals("v1", health.contractVersion());
        assertTrue(health.supportedParsers().contains("DELIMITED"));

        SshFingerprintResponse fp = client.fetchSshFingerprint("10.10.0.5", 22);
        assertEquals(TRUSTED_FP, fp.fingerprintSha256());

        PhysicalDevice device = buildDevice();
        PrivilegeSpec priv = new PrivilegeSpec("SUDO_NOPASSWD", "sudo -n {{command}}", null, List.of());
        LockSpec lock = new LockSpec(List.of(100), "lock", 3, List.of(15));

        InventoryResponse inv =
                client.collectInventory(
                                device,
                                Map.of("LC_ALL", "C"),
                                Map.of("id", "debian"),
                                new InventorySpec(
                                        "dpkg-query -W",
                                        false,
                                        60,
                                        "DELIMITED",
                                        Map.of(),
                                        "pkg:deb/debian/{{name}}@{{version}}"),
                                priv,
                                null)
                        .response();
        assertEquals(1, inv.totalParsed());
        assertEquals("pkg:deb/debian/openssl@3.0.13", inv.packages().getFirst().purl());

        UpdateCheckResponse upd =
                client.checkUpdates(
                                device,
                                Map.of("LC_ALL", "C"),
                                false,
                                new UpdateCheckSpec(
                                        null,
                                        false,
                                        "apt-get -s upgrade",
                                        false,
                                        List.of(0),
                                        List.of(0, 100),
                                        120,
                                        "REGEX",
                                        Map.of()),
                                priv,
                                lock)
                        .response();
        assertEquals(1, upd.updates().size());
        assertEquals("curl", upd.updates().getFirst().packageName());

        List<PatchStreamFrame> frames = new ArrayList<>();
        client.executePatchStreaming(
                UUID.randomUUID(),
                device,
                Map.of("LC_ALL", "C"),
                "FULL_UPGRADE",
                List.of(),
                new PatchExecSpec(
                        null,
                        "apt-get update",
                        "apt-get -y upgrade",
                        null,
                        null,
                        null,
                        List.of(0),
                        true,
                        Map.of(),
                        600,
                        120),
                priv,
                lock,
                frames::add);
        assertEquals(3, frames.size());
        assertEquals("PHASE", frames.get(0).type());
        assertEquals("LOG", frames.get(1).type());
        assertEquals("RESULT", frames.get(2).type());
        assertEquals("SUCCEEDED", frames.get(2).status());
        assertNotNull(frames.get(1).line());
    }
}

