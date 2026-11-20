package com.gnm.pkgmgmt.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gnm.model.Credential;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.ThreatEvent;
import com.gnm.pkgmgmt.client.CredentialResolver.ResolvedCandidate;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.ApiError;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.AuthConfig;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.CancelResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.HealthResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventoryRequest;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventoryResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventorySpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.LockSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchExecSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchExecuteRequest;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchStreamFrame;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PrivilegeSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.ProbeRequest;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.ProbeResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.SshFingerprintRequest;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.SshFingerprintResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.SshTarget;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckRequest;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckResponse;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckSpec;
import com.gnm.service.VaultEngine;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PkgWorkerClient {

    private static final Logger log = Logger.getLogger(PkgWorkerClient.class);

    public record AuthenticatedCallResult<T>(
            T response,
            UUID credentialId,
            String credentialUsername,
            boolean usedFallback,
            String resolutionSource) {}

    private final VaultEngine vaultEngine;
    private final CredentialResolver credentialResolver;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final byte[] authSecret;
    private final BiConsumer<PhysicalDevice, String> hostKeyMismatchReporter;

    @Inject
    public PkgWorkerClient(
            VaultEngine vaultEngine,
            CredentialResolver credentialResolver,
            ObjectMapper objectMapper,
            @ConfigProperty(name = "gnm.pkg-worker.base-url", defaultValue = "http://pkg_worker:8081")
                    String baseUrl,
            @ConfigProperty(
                            name = "gnm.pkg-worker.auth-secret",
                            defaultValue = "dev-only-local-pkg-worker-secret-32b!")
                    String authSecret,
            Event<ThreatEvent> threatBroadcaster) {
        this(
                vaultEngine,
                credentialResolver,
                objectMapper,
                baseUrl,
                authSecret,
                (device, actualFp) -> recordHostKeyMismatchThreat(device, actualFp, threatBroadcaster));
    }

    public PkgWorkerClient(
            VaultEngine vaultEngine,
            CredentialResolver credentialResolver,
            ObjectMapper objectMapper,
            String baseUrl,
            String authSecret,
            BiConsumer<PhysicalDevice, String> hostKeyMismatchReporter) {
        this.vaultEngine = vaultEngine;
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authSecret = authSecret.getBytes(StandardCharsets.UTF_8);
        this.hostKeyMismatchReporter = hostKeyMismatchReporter;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public HealthResponse getHealth() {
        return sendSignedJson("GET", "/v1/health", null, HealthResponse.class, Duration.ofSeconds(10));
    }

    public SshFingerprintResponse fetchSshFingerprint(String host, int port) {
        return sendSignedJson(
                "POST",
                "/v1/ssh/fingerprint",
                new SshFingerprintRequest(host, port > 0 ? port : 22, 10),
                SshFingerprintResponse.class,
                Duration.ofSeconds(15));
    }

    public AuthenticatedCallResult<ProbeResponse> probeDevice(
            PhysicalDevice device, Map<String, String> verifyCommands) {
        return executeWithCredentialFallback(
                device,
                target ->
                        sendSignedJson(
                                "POST",
                                "/v1/probe",
                                new ProbeRequest(UUID.randomUUID(), target, verifyCommands),
                                ProbeResponse.class,
                                Duration.ofSeconds(30)));
    }

    public AuthenticatedCallResult<InventoryResponse> collectInventory(
            PhysicalDevice device,
            Map<String, String> envVars,
            Map<String, String> osFacts,
            InventorySpec inventorySpec,
            PrivilegeSpec privilegeSpec,
            Integer dryRunPreviewLimit) {
        int timeoutSec =
                inventorySpec.timeoutSeconds() > 0 ? inventorySpec.timeoutSeconds() + 15 : 75;
        return executeWithCredentialFallback(
                device,
                target ->
                        sendSignedJson(
                                "POST",
                                "/v1/inventory",
                                new InventoryRequest(
                                        UUID.randomUUID(),
                                        target,
                                        envVars,
                                        osFacts,
                                        inventorySpec,
                                        privilegeSpec,
                                        dryRunPreviewLimit),
                                InventoryResponse.class,
                                Duration.ofSeconds(timeoutSec)));
    }

    public AuthenticatedCallResult<UpdateCheckResponse> checkUpdates(
            PhysicalDevice device,
            Map<String, String> envVars,
            boolean skipRefresh,
            UpdateCheckSpec updateCheckSpec,
            PrivilegeSpec privilegeSpec,
            LockSpec lockSpec) {
        int timeoutSec =
                updateCheckSpec.timeoutSeconds() > 0 ? updateCheckSpec.timeoutSeconds() + 30 : 150;
        return executeWithCredentialFallback(
                device,
                target ->
                        sendSignedJson(
                                "POST",
                                "/v1/updates/check",
                                new UpdateCheckRequest(
                                        UUID.randomUUID(),
                                        target,
                                        envVars,
                                        skipRefresh,
                                        updateCheckSpec,
                                        privilegeSpec,
                                        lockSpec),
                                UpdateCheckResponse.class,
                                Duration.ofSeconds(timeoutSec)));
    }

    public AuthenticatedCallResult<Void> executePatchStreaming(
            UUID executionId,
            PhysicalDevice device,
            Map<String, String> envVars,
            String mode,
            List<String> packages,
            PatchExecSpec patchExecSpec,
            PrivilegeSpec privilegeSpec,
            LockSpec lockSpec,
            Consumer<PatchStreamFrame> frameConsumer) {
        int timeoutSec =
                patchExecSpec.timeoutSeconds() > 0 ? patchExecSpec.timeoutSeconds() + 60 : 1860;
        return executeWithCredentialFallback(
                device,
                target -> {
                    sendSignedNdjsonStream(
                            "/v1/patch/execute",
                            new PatchExecuteRequest(
                                    executionId,
                                    target,
                                    envVars,
                                    mode,
                                    packages,
                                    patchExecSpec,
                                    privilegeSpec,
                                    lockSpec),
                            Duration.ofSeconds(timeoutSec),
                            frameConsumer);
                    return null;
                });
    }

    public CancelResponse cancelExecution(UUID executionId) {
        return sendSignedJson(
                "POST",
                "/v1/executions/" + executionId + "/cancel",
                Map.of(),
                CancelResponse.class,
                Duration.ofSeconds(10));
    }

    private <T> AuthenticatedCallResult<T> executeWithCredentialFallback(
            PhysicalDevice device, Function<SshTarget, T> action) {
        if (!vaultEngine.isUnsealed()) {
            throw new PkgWorkerException("VAULT_SEALED", "Credential Vault is sealed", 403);
        }

        String hostIp = resolveCurrentIp(device);
        NetworkService sshService =
                CredentialResolver.findSshService(device)
                        .orElseThrow(
                                () ->
                                        new PkgWorkerException(
                                                "SSH_SERVICE_MISSING",
                                                "No SSH service configured on device",
                                                400));

        // TOFU gate (Invariant #8): NEVER decrypt or send credentials if host key is not trusted.
        if (sshService.sshHostKey == null
                || sshService.sshHostKey.isBlank()
                || !Boolean.TRUE.equals(sshService.sshHostKeyTrusted)) {
            throw new PkgWorkerException(
                    "SSH_HOST_KEY_NOT_TRUSTED",
                    "SSH host key is not trusted for this device",
                    412);
        }

        List<ResolvedCandidate> candidates = credentialResolver.resolveCandidates(device);
        if (candidates.isEmpty()) {
            throw new PkgWorkerException(
                    "MISSING_CREDENTIAL",
                    "No PKG_MANAGEMENT or SSH credential assigned to device",
                    400);
        }

        PkgWorkerException lastAuthFailure = null;
        for (int i = 0; i < candidates.size(); i++) {
            ResolvedCandidate candidate = candidates.get(i);
            boolean usedFallback = i > 0 || candidate.fallback();
            try {
                SshTarget target =
                        buildDecryptedTarget(
                                hostIp,
                                sshService.sshHostKey,
                                candidate.credential(),
                                candidate.effectivePort());
                T result = action.apply(target);
                if (i > 0) {
                    log.warnf(
                            "Primary PKG_MANAGEMENT credential failed for device %s; succeeded using fallback credential %s (%s)",
                            device.id,
                            candidate.credential().id,
                            candidate.credential().username);
                }
                return new AuthenticatedCallResult<>(
                        result,
                        candidate.credential().id,
                        candidate.credential().username,
                        usedFallback,
                        candidate.resolutionSource());
            } catch (PkgWorkerException ex) {
                if ("HOST_KEY_MISMATCH".equals(ex.errorCode())) {
                    if (hostKeyMismatchReporter != null) {
                        hostKeyMismatchReporter.accept(device, ex.actualFingerprint());
                    }
                    // NEVER retry with fallback credential on HOST_KEY_MISMATCH (potential MITM).
                    throw ex;
                }
                if ("AUTH_FAILED".equals(ex.errorCode()) && i + 1 < candidates.size()) {
                    lastAuthFailure = ex;
                    continue;
                }
                throw ex;
            }
        }
        throw lastAuthFailure != null
                ? lastAuthFailure
                : new PkgWorkerException("AUTH_FAILED", "SSH authentication failed", 502);
    }

    private SshTarget buildDecryptedTarget(
            String hostIp, String trustedHostKey, Credential cred, int port) {
        byte[] decryptedBytes = null;
        char[] secretChars = null;
        try {
            decryptedBytes = vaultEngine.decrypt(cred.encryptedPayload, cred.noncePayload);
            CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(decryptedBytes));
            secretChars = new char[cb.remaining()];
            cb.get(secretChars);
            if (cb.hasArray()) {
                Arrays.fill(cb.array(), '\0');
            }
            return new SshTarget(
                    "SSH",
                    hostIp,
                    port,
                    cred.username != null && !cred.username.isBlank() ? cred.username : "root",
                    trustedHostKey,
                    new AuthConfig(cred.credentialType.name(), new String(secretChars), null),
                    30);
        } finally {
            if (decryptedBytes != null) {
                Arrays.fill(decryptedBytes, (byte) 0);
            }
            if (secretChars != null) {
                Arrays.fill(secretChars, '\0');
            }
        }
    }

    private static String resolveCurrentIp(PhysicalDevice device) {
        if (device == null || device.identities == null) {
            throw new PkgWorkerException(
                    "DEVICE_NO_IP", "Device has no active IP address", 400);
        }
        return device.identities.stream()
                .filter(i -> i != null && i.current && i.ipAddress != null && !i.ipAddress.isBlank())
                .map((NetworkIdentity i) -> i.ipAddress)
                .findFirst()
                .orElseThrow(
                        () ->
                                new PkgWorkerException(
                                        "DEVICE_NO_IP",
                                        "Device has no active IP address",
                                        400));
    }

    private <T> T sendSignedJson(
            String method, String path, Object payload, Class<T> responseType, Duration timeout) {
        byte[] bodyBytes = null;
        try {
            bodyBytes =
                    payload == null ? new byte[0] : objectMapper.writeValueAsBytes(payload);
            String ts = String.valueOf(Instant.now().getEpochSecond());
            String sig =
                    PkgWorkerHmacSigner.computeSignature(authSecret, method, path, ts, bodyBytes);

            HttpRequest.Builder reqBuilder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + path))
                            .timeout(timeout)
                            .header(PkgWorkerHmacSigner.HEADER_TIMESTAMP, ts)
                            .header(PkgWorkerHmacSigner.HEADER_SIGNATURE, sig);

            if ("GET".equalsIgnoreCase(method)) {
                reqBuilder.GET();
            } else {
                reqBuilder
                        .header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
            }

            HttpResponse<byte[]> resp =
                    httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return objectMapper.readValue(resp.body(), responseType);
            }
            throw parseWorkerError(resp.statusCode(), resp.body());
        } catch (PkgWorkerException e) {
            throw e;
        } catch (Exception e) {
            throw new PkgWorkerException(
                    "WORKER_UNREACHABLE",
                    "Failed to communicate with gnm-pkg-worker: " + e.getMessage(),
                    502);
        } finally {
            if (bodyBytes != null) {
                Arrays.fill(bodyBytes, (byte) 0);
            }
        }
    }

    private void sendSignedNdjsonStream(
            String path,
            Object payload,
            Duration timeout,
            Consumer<PatchStreamFrame> frameConsumer) {
        byte[] bodyBytes = null;
        try {
            bodyBytes = objectMapper.writeValueAsBytes(payload);
            String ts = String.valueOf(Instant.now().getEpochSecond());
            String sig =
                    PkgWorkerHmacSigner.computeSignature(authSecret, "POST", path, ts, bodyBytes);

            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + path))
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .header(PkgWorkerHmacSigner.HEADER_TIMESTAMP, ts)
                            .header(PkgWorkerHmacSigner.HEADER_SIGNATURE, sig)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                            .build();

            HttpResponse<InputStream> resp =
                    httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                try (InputStream errStream = resp.body()) {
                    throw parseWorkerError(resp.statusCode(), errStream.readAllBytes());
                }
            }

            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    PatchStreamFrame frame =
                            objectMapper.readValue(trimmed, PatchStreamFrame.class);
                    frameConsumer.accept(frame);
                }
            }
        } catch (PkgWorkerException e) {
            throw e;
        } catch (Exception e) {
            throw new PkgWorkerException(
                    "WORKER_STREAM_FAILED",
                    "Streaming execution from gnm-pkg-worker failed: " + e.getMessage(),
                    502);
        } finally {
            if (bodyBytes != null) {
                Arrays.fill(bodyBytes, (byte) 0);
            }
        }
    }

    private PkgWorkerException parseWorkerError(int status, byte[] body) {
        try {
            if (body != null && body.length > 0) {
                ApiError err = objectMapper.readValue(body, ApiError.class);
                if (err != null && err.errorCode() != null) {
                    return new PkgWorkerException(
                            err.errorCode(),
                            err.message() != null ? err.message() : "Worker error",
                            status,
                            err.actualFingerprint(),
                            err.exitCode());
                }
            }
        } catch (Exception ignored) {
            // Fall through to generic worker error.
        }
        return new PkgWorkerException(
                "INTERNAL_ERROR", "gnm-pkg-worker returned HTTP " + status, status);
    }

    private static void recordHostKeyMismatchThreat(
            PhysicalDevice device, String actualFp, Event<ThreatEvent> threatBroadcaster) {
        if (device == null || device.id == null) {
            return;
        }
        try {
            String ip =
                    device.identities == null
                            ? "unknown"
                            : device.identities.stream()
                                    .filter(i -> i != null && i.current)
                                    .map(i -> i.ipAddress)
                                    .findFirst()
                                    .orElse("unknown");
            String desc =
                    "SSH Host Key mutation detected! Remote host identification has changed. Key: "
                            + (actualFp != null ? actualFp : "unknown");
            QuarkusTransaction.requiringNew()
                    .run(
                            () -> {
                                ThreatEvent existing =
                                        ThreatEvent.find(
                                                        "physicalDeviceId = ?1 and description = ?2 and resolved = false",
                                                        device.id,
                                                        desc)
                                                .firstResult();
                                if (existing != null) {
                                    existing.detectedAt = Instant.now();
                                    existing.persist();
                                    if (threatBroadcaster != null) {
                                        threatBroadcaster.fireAsync(existing);
                                    }
                                } else {
                                    ThreatEvent threat = new ThreatEvent();
                                    threat.severity = "HIGH";
                                    threat.description = desc;
                                    threat.physicalDeviceId = device.id;
                                    threat.ipAddress = ip;
                                    threat.detectedAt = Instant.now();
                                    threat.persist();
                                    if (threatBroadcaster != null) {
                                        threatBroadcaster.fireAsync(threat);
                                    }
                                }
                            });
        } catch (Exception e) {
            log.error("Failed to record SSH host key mismatch threat event", e);
        }
    }
}

