package com.gnm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import io.restassured.response.Response;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.List;

import com.gnm.model.PhysicalDevice;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.NetworkService;
import com.gnm.model.Credential;
import com.gnm.model.enums.DeviceType;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.ManagementState;
import com.gnm.service.VaultEngine;
import com.gnm.fingerprint.FingerprintEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
public class RemoteAccessTest extends AbstractE2ETest {

    @TestHTTPResource("/ws/events")
    URI eventsUri;

    @TestHTTPResource("/ws/terminal")
    URI terminalUri;

    @Inject
    VaultEngine vaultEngine;

    @Inject
    FingerprintEngine fingerprintEngine;

    public static class TestWebSocketListener implements WebSocket.Listener {
        public final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messages.add(data.toString());
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }
    }

    @BeforeEach
    public void setup() {
        if (!vaultEngine.isInitialized()) {
            given().contentType("application/json").when().post("/api/vault/init").then().statusCode(200);
        }
        if (!vaultEngine.isUnsealed()) {
            given().contentType("application/json").when().post("/api/vault/unseal").then().statusCode(200);
        }
    }

    @BeforeEach
    public void clearDatabase() {
        fingerprintEngine.flushAndClear();
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            com.gnm.model.Credential.deleteAll();
            com.gnm.model.NetworkService.deleteAll();
            com.gnm.model.NetworkIdentity.deleteAll();
            com.gnm.model.PhysicalDevice.deleteAll();
        });
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testWebSocketRealtimeUiUpdates() throws Exception {
        // Scenario 7.1
        String wsUri = eventsUri.toString().replace("http://", "ws://").replace("https://", "wss://");
        TestWebSocketListener listener = new TestWebSocketListener();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(wsUri), listener).join();

        // Trigger a backend state change: Create a sighting
        String ip = "192.168.100.20"; // Router sim
        waitForSsh(ip);
        String discoverPayload = "{\"ipAddress\": \"" + ip + "\"}";
        given()
                .contentType("application/json")
                .body(discoverPayload)
                .when().post("/api/devices/discover")
                .then().statusCode(202);

        // Verify JSON event is pushed
        boolean eventReceived = false;
        long endTime = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < endTime) {
            String msg = listener.messages.poll(100, TimeUnit.MILLISECONDS);
            if (msg != null && (msg.contains("STATUS_CHANGE") || msg.contains("NEW_DEVICE")) && msg.contains(ip)) {
                eventReceived = true;
                break;
            }
        }
        assertTrue(eventReceived, "Should receive real-time DeviceEvent over WebSocket");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").join();
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    public void testSshTerminalProxySession() throws Exception {
        // Scenario 7.2
        String ip = "192.168.100.10"; // Linux server with SSH
        
        // 1. Discover the device so it's in the DB
        waitForSsh(ip);
        String discoverPayload = "{\"ipAddress\": \"" + ip + "\"}";
        given().contentType("application/json").body(discoverPayload).when().post("/api/devices/discover").then().statusCode(202);
        
        // Wait for device to be created
        PhysicalDevice pd = null;
        for (int i = 0; i < 50; i++) {
            NetworkIdentity id = NetworkIdentity.find("ipAddress", ip).firstResult();
            if (id != null) {
                pd = id.physicalDevice;
                break;
            }
            Thread.sleep(100);
        }
        assertNotNull(pd, "Device should be discovered");

        // Create credential (password: testpass)
        Credential cred = createRealCredential(pd);
        
        // Ensure SSH host key is trusted (bypass TOFU)
        trustSshHostKey(pd, cred);

        // Connect via Terminal WebSocket
        String wsUri = terminalUri.toString().replace("http://", "ws://").replace("https://", "wss://") + "/" + pd.id + "/" + cred.id;
        TestWebSocketListener listener = new TestWebSocketListener();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(wsUri), listener).join();

        // Send a command to the terminal periodically until we get output
        boolean receivedOutput = false;
        long endTime = System.currentTimeMillis() + 10000;
        StringBuilder output = new StringBuilder();
        long nextSend = System.currentTimeMillis();
        while (System.currentTimeMillis() < endTime) {
            if (System.currentTimeMillis() > nextSend) {
                // Wrap in JSON as expected by the TerminalWebSocket onMessage
                ws.sendText("{\"type\":\"input\",\"data\":\"whoami\\n\"}", true);
                nextSend = System.currentTimeMillis() + 500;
            }
            String msg = listener.messages.poll(100, TimeUnit.MILLISECONDS);
            if (msg != null) {
                output.append(msg);
                if (output.toString().contains("testuser")) {
                    receivedOutput = true;
                    break;
                }
            }
        }
        assertTrue(receivedOutput, "Should receive streamed terminal output from the SSH session");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").join();
    }

    @Transactional
    protected Credential createRealCredential(PhysicalDevice pd) {
        String plainPassword = "testpass"; // testuser:testpass is built into ne-linux-server
        String payload = """
            {
                "label": "SSH Credential",
                "type": "PASSWORD",
                "username": "testuser",
                "secret": "%s"
            }
        """.formatted(plainPassword);
        
        Response res = given()
            .contentType("application/json")
            .body(payload)
            .when().post("/api/credentials/device/" + pd.id.toString());
            
        res.then().statusCode(200);
        String credId = res.jsonPath().getString("id");
        return Credential.findById(java.util.UUID.fromString(credId));
    }
    
    protected void trustSshHostKey(PhysicalDevice pd, Credential cred) throws Exception {
        NetworkService ns = NetworkService.find("physicalDevice.id = ?1 and serviceType = 'SSH'", pd.id).firstResult();
        if (ns == null) {
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                NetworkService newNs = new NetworkService();
                newNs.physicalDevice = pd;
                newNs.serviceType = "SSH";
                newNs.port = 22;
                newNs.protocol = "TCP";
                newNs.firstSeen = java.time.Instant.now();
                newNs.lastSeen = java.time.Instant.now();
                newNs.persist();
            });
            ns = NetworkService.find("physicalDevice.id = ?1 and serviceType = 'SSH'", pd.id).firstResult();
        }
        
        if (ns.sshHostKey == null) {
            // Simulate TOFU by connecting once via WebSocket
            String wsUri = terminalUri.toString().replace("http://", "ws://").replace("https://", "wss://") + "/" + pd.id + "/" + cred.id;
            TestWebSocketListener listener = new TestWebSocketListener();
            WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(wsUri), listener).join();
            
            // Wait for it to fail and set the key
            long endTime = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < endTime) {
                // Fetch fresh list by forcing clear or running in transaction
                List<NetworkService> list = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> 
                    NetworkService.<NetworkService>list("physicalDevice.id = ?1 and serviceType = 'SSH'", pd.id)
                );
                for (NetworkService s : list) {
                    if (s.sshHostKey != null) {
                        ns = s;
                        break;
                    }
                }
                if (ns != null && ns.sshHostKey != null) break;
                Thread.sleep(100);
            }
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done").join();
        }
        
        if (ns != null && ns.sshHostKey != null) {
            final java.util.UUID nsId = ns.id;
            io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
                NetworkService existingNs = NetworkService.findById(nsId);
                existingNs.sshHostKeyTrusted = true;
                existingNs.persist();
            });
        } else {
            throw new RuntimeException("Failed to obtain SSH host key from TOFU process");
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
