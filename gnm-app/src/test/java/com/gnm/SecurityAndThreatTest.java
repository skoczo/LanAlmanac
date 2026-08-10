package com.gnm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;

import com.gnm.model.PhysicalDevice;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.FingerprintVector;
import com.gnm.model.NetworkService;
import com.gnm.model.ThreatEvent;
import com.gnm.model.Credential;
import com.gnm.model.enums.DeviceType;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.ManagementState;
import io.quarkus.test.common.http.TestHTTPResource;
import java.net.URI;
import java.net.http.WebSocket;
import java.net.http.HttpClient;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
public class SecurityAndThreatTest extends AbstractE2ETest {

    public static class TestWebSocketListener implements WebSocket.Listener {
        public final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messages.add(data.toString());
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            messages.add("CLOSE:" + statusCode);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
    }

    @TestHTTPResource("/ws/terminal")
    URI terminalUri;

    @Inject
    com.gnm.service.VaultEngine vaultEngine;

    @BeforeEach
    @Transactional
    public void setup() {
        com.gnm.model.ThreatEvent.deleteAll();
        com.gnm.model.Credential.deleteAll();
        com.gnm.model.NetworkService.deleteAll();
        com.gnm.model.NetworkIdentity.deleteAll();
        com.gnm.model.PhysicalDevice.deleteAll();
        
        if (!vaultEngine.isInitialized()) {
            given().contentType("application/json").when().post("/api/vault/init").then().statusCode(200);
        }
        if (!vaultEngine.isUnsealed()) {
            given().contentType("application/json").when().post("/api/vault/unseal").then().statusCode(200);
        }
    }

    @AfterEach
    @Transactional
    public void cleanup() {
        com.gnm.model.ThreatEvent.deleteAll();
        com.gnm.model.Credential.deleteAll();
        com.gnm.model.NetworkService.deleteAll();
        com.gnm.model.NetworkIdentity.deleteAll();
        com.gnm.model.PhysicalDevice.deleteAll();
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testSshHostKeyChangeDetectedOnPeriodicScan() throws Exception {
        // Given: The ne-linux-server (192.168.100.10) is discovered and its original
        // key is "fake-old-key"
        String ip = "192.168.100.10";
        setupFakeSshHostKey(ip, "fake-old-key");

        // Force port scanning in test environment
        System.setProperty("forceNetworkScan", "true");
        try {
            // When: The periodic scan (or manual discovery) hits the device and fetches its REAL ssh key
            waitForSsh(ip);
        String scanPayload = "{\"ipAddress\": \"" + ip + "\"}";
        given()
                .contentType("application/json")
                .body(scanPayload)
                .when().post("/api/devices/discover")
                .then().statusCode(202);

        // Then: A ThreatEvent should be created indicating an SSH host key mutation
        boolean threatFound = false;
        for (int i = 0; i < 50; i++) {
            List<ThreatEvent> threats = ThreatEvent.list("ipAddress", ip);
            if (!threats.isEmpty()) {
                ThreatEvent threat = threats.get(0);
                if (threat.description != null && threat.description.contains("SSH Host Key mutation")) {
                    threatFound = true;
                    assertEquals("HIGH", threat.severity);
                    assertEquals(false, threat.resolved);
                    break;
                }
            }
            Thread.sleep(200);
        }
        assertTrue(threatFound, "A HIGH severity ThreatEvent should be created for SSH key mismatch");
        } finally {
            System.clearProperty("forceNetworkScan");
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testAlarmAutoMitigationOnHostKeyReversion() throws Exception {
        // Given: The device has an unresolved ThreatEvent for a key mismatch
        String ip = "192.168.100.10";
        setupFakeSshHostKey(ip, "fake-old-key");

        // Let's trigger the mismatch first
        System.setProperty("forceNetworkScan", "true");
        try {
            waitForSsh(ip);
            String scanPayload = "{\"ipAddress\": \"" + ip + "\"}";
            given().contentType("application/json").body(scanPayload).when().post("/api/devices/discover").then()
                    .statusCode(202);

        ThreatEvent activeThreat = null;
        for (int i = 0; i < 50; i++) {
            List<ThreatEvent> threats = ThreatEvent.list("ipAddress = ?1 and resolved = false", ip);
            if (!threats.isEmpty()) {
                activeThreat = threats.get(0);
                break;
            }
            Thread.sleep(200);
        }
        assertNotNull(activeThreat, "Threat should be created for initial mismatch");

        // Now, let's simulate the key reverting back to "fake-old-key"!
        // We do this by changing the real container's key in the database? No, the
        // FingerprintEngine will fetch the REAL key.
        // Wait, to simulate reversion, we need to add the REAL key to
        // historical.sshHostKeys, and then the next scan should resolve it.
        // Let's find out what the real key is by reading it from the Threat
        // description, then adding it to historical keys,
        // wait, no, the auto-mitigation only happens if the NEW key fetched is IN
        // historical keys.
        // So we just need to add the REAL key to the database, trigger a scan, and it
        // will resolve!
        String desc = activeThreat.description;
        String realKey = desc.substring(desc.lastIndexOf("Key: ") + 5).trim();

        addKeyToHistorical(ip, realKey);

        // When: The device is scanned again, returning the real key (which is now in
        // historical keys)
        given().contentType("application/json").body(scanPayload).when().post("/api/devices/discover").then()
                .statusCode(202);

        // Then: The ThreatEvent should be auto-mitigated (resolved = true)
        boolean resolved = false;
        for (int i = 0; i < 75; i++) {
            final java.util.UUID threatId = activeThreat.id;
            ThreatEvent t = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> ThreatEvent.findById(threatId));
            if (t != null && t.resolved) {
                resolved = true;
                assertTrue(t.notes.contains("Key reverted to original trusted value"));
                break;
            }
            if (i % 10 == 0 && i > 0) {
                // Retry scan every 2 seconds just in case it was missed
                given().contentType("application/json").body(scanPayload).when().post("/api/devices/discover");
            }
            Thread.sleep(200);
        }
        assertTrue(resolved, "Threat should be auto-mitigated when key reverts to historical value");
        } finally {
            System.clearProperty("forceNetworkScan");
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testConnectionBlockedAndAlarmRaisedOnManualConnect() throws Exception {
        // Given: We have a device with a fake trusted SSH key
        String ip = "192.168.100.10";
        setupFakeSshHostKey(ip, "fake-old-key");

        NetworkIdentity id = NetworkIdentity.find("ipAddress", ip).firstResult();
        PhysicalDevice pd = id.physicalDevice;
        Credential cred = setupMockCredential(pd);

        waitForSsh(ip);

        // When: We try to connect via WebSocket (manual terminal connect)
        String wsUri = terminalUri.toString().replace("http://", "ws://").replace("https://", "wss://") + "/" + pd.id + "/" + cred.id;
        TestWebSocketListener listener = new TestWebSocketListener();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(wsUri), listener).join();

        // Then: The connection should be rejected and an alarm should be raised
        boolean rejected = false;
        long endTime = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < endTime) {
            String msg = listener.messages.poll(100, TimeUnit.MILLISECONDS);
            if (msg != null && msg.contains("REMOTE HOST IDENTIFICATION HAS CHANGED!")) {
                rejected = true;
                break;
            }
        }
        assertTrue(rejected, "WebSocket should receive critical warning about changed host key");

        // Check for ThreatEvent
        boolean threatCreated = false;
        for (int i = 0; i < 50; i++) {
            List<ThreatEvent> threats = ThreatEvent.list("ipAddress", ip);
            if (!threats.isEmpty()) {
                threatCreated = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(threatCreated, "ThreatEvent should be created on manual connect mismatch");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").join();
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testHostKeyTrustOnFirstConnectTofu() throws Exception {
        // Given: We have a device with NO sshHostKey stored yet
        String ip = "192.168.100.20"; // Router sim
        setupFakeSshHostKey(ip, null); // Set it to null

        NetworkIdentity id = NetworkIdentity.find("ipAddress", ip).firstResult();
        PhysicalDevice pd = id.physicalDevice;
        Credential cred = setupMockCredential(pd);

        waitForSsh(ip);

        // When: We try to connect via WebSocket
        String wsUri = terminalUri.toString().replace("http://", "ws://").replace("https://", "wss://") + "/" + pd.id + "/" + cred.id;
        TestWebSocketListener listener = new TestWebSocketListener();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(wsUri), listener).join();

        // Then: We should get a TOFU warning
        boolean tofuTriggered = false;
        long endTime = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < endTime) {
            String msg = listener.messages.poll(100, TimeUnit.MILLISECONDS);
            if (msg != null && msg.contains("First time connecting to this host")) {
                tofuTriggered = true;
                break;
            }
        }
        assertTrue(tofuTriggered, "WebSocket should receive TOFU warning on first connection");

        // Verify the NetworkService now has a key, but is NOT trusted
        NetworkService ns = NetworkService.find("physicalDevice.id = ?1 and serviceType = 'SSH'", pd.id).firstResult();
        assertNotNull(ns.sshHostKey, "sshHostKey should be populated after first connect");
        assertEquals(false, ns.sshHostKeyTrusted, "sshHostKey should not be automatically trusted");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").join();
    }

    @Transactional
    protected Credential setupMockCredential(PhysicalDevice pd) {
        com.gnm.model.Credential cred = new com.gnm.model.Credential();
        cred.physicalDevice = pd;
        cred.label = "Mock Admin Credential";
        cred.username = "testuser";
        cred.credentialType = com.gnm.model.enums.CredentialType.PASSWORD;
        // Properly encrypt the mock payload
        com.gnm.service.VaultEngine.EncryptedRecord record = vaultEngine.encrypt("testpass".getBytes(StandardCharsets.UTF_8));
        cred.encryptedPayload = record.ciphertext;
        cred.noncePayload = record.iv;
        cred.createdAt = java.time.Instant.now();
        cred.updatedAt = java.time.Instant.now();
        cred.persist();
        return cred;
    }

    @Transactional
    protected void setupFakeSshHostKey(String ip, String fakeKey) {
        NetworkIdentity id = NetworkIdentity.find("ipAddress", ip).firstResult();
        PhysicalDevice pd;
        if (id == null) {
            pd = new PhysicalDevice();
            pd.displayName = "Mock Server";
            pd.deviceType = DeviceType.SERVER;
            pd.managementState = ManagementState.MANAGED;
            pd.status = DeviceStatus.ONLINE;
            pd.firstSeen = java.time.Instant.now();
            pd.lastSeen = java.time.Instant.now();
            pd.persist();

            id = new NetworkIdentity();
            id.physicalDevice = pd;
            id.ipAddress = ip;
            id.macAddress = "00:00:00:00:00:00";
            id.current = true;
            id.firstSeen = java.time.Instant.now();
            id.lastSeen = java.time.Instant.now();
            id.persist();
        } else {
            pd = id.physicalDevice;
        }

        FingerprintVector fp = FingerprintVector.find("physicalDevice.id", pd.id).firstResult();
        if (fp == null) {
            fp = new FingerprintVector();
            fp.physicalDevice = pd;
            fp.version = 1;
            fp.capturedAt = java.time.Instant.now();
        }
        if (fakeKey != null) {
            if (fp.sshHostKeys == null)
                fp.sshHostKeys = new java.util.ArrayList<>();
            if (!fp.sshHostKeys.contains(fakeKey))
                fp.sshHostKeys.add(fakeKey);
        }
        fp.persist();

        NetworkService ns = NetworkService.find("physicalDevice.id = ?1 and serviceType = 'SSH'", pd.id).firstResult();
        if (ns == null) {
            ns = new NetworkService();
            ns.physicalDevice = pd;
            ns.serviceType = "SSH";
            ns.port = 22;
            ns.protocol = "TCP";
            ns.firstSeen = java.time.Instant.now();
            ns.lastSeen = java.time.Instant.now();
        }
        ns.sshHostKey = fakeKey;
        ns.sshHostKeyTrusted = fakeKey != null;
        ns.persist();
    }

    @Transactional
    protected void addKeyToHistorical(String ip, String realKey) {
        NetworkIdentity id = NetworkIdentity.find("ipAddress", ip).firstResult();
        PhysicalDevice pd = id.physicalDevice;
        FingerprintVector fp = FingerprintVector.find("physicalDevice.id", pd.id).firstResult();
        if (!fp.sshHostKeys.contains(realKey)) {
            fp.sshHostKeys.add(realKey);
            fp.persist();
        }
    }

    protected void waitForSsh(String ip) throws Exception {
        for (int i = 0; i < 50; i++) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(ip, 22), 200);
                return; // Connected successfully
            } catch (Exception e) {
                Thread.sleep(200);
            }
        }
        throw new RuntimeException("SSH port never opened on " + ip);
    }
}
