package com.gnm.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.gnm.model.Credential;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.CredentialType;
import com.gnm.service.VaultEngine;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.jboss.logging.Logger;
import io.quarkus.narayana.jta.QuarkusTransaction;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@WebSocket(path = "/ws/terminal/{deviceId}/{credentialId}")
public class TerminalWebSocket {

    private static final Logger log = Logger.getLogger(TerminalWebSocket.class);

    @Inject
    VaultEngine vaultEngine;

    private static final ObjectMapper mapper = new ObjectMapper();

    // Track active sessions to pipe input correctly and clean up on close
    private final Map<String, SshSessionContext> activeSessions = new ConcurrentHashMap<>();

    @OnOpen
    @Transactional
    public void onOpen(WebSocketConnection connection, @PathParam("deviceId") String deviceIdStr, @PathParam("credentialId") String credentialIdStr) {
        log.infof("Terminal WebSocket opened for device %s", deviceIdStr);
        
        if (!vaultEngine.isUnsealed()) {
            connection.sendTextAndAwait("Error: Vault is sealed.\r\n");
            connection.close();
            return;
        }

        UUID deviceId = UUID.fromString(deviceIdStr);
        UUID credentialId = UUID.fromString(credentialIdStr);

        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            connection.sendTextAndAwait("Error: Device not found.\r\n");
            connection.close();
            return;
        }

        Credential cred = Credential.findById(credentialId);
        if (cred == null || !cred.physicalDevice.id.equals(device.id)) {
            connection.sendTextAndAwait("Error: Credential not found.\r\n");
            connection.close();
            return;
        }

        String ipAddress = device.identities.stream()
                .filter(id -> id.current)
                .map(id -> id.ipAddress)
                .findFirst()
                .orElse(null);

        if (ipAddress == null) {
            connection.sendTextAndAwait("Error: Device has no active IP address.\r\n");
            connection.close();
            return;
        }

        int port = cred.port != null ? cred.port : 22;
        NetworkService targetService = null;
        for (NetworkService svc : device.services) {
            if (svc.port != null && svc.port == port && "SSH".equalsIgnoreCase(svc.serviceType)) {
                targetService = svc;
                break;
            }
        }
        if (targetService == null) {
            targetService = new NetworkService();
            targetService.physicalDevice = device;
            targetService.serviceType = "SSH";
            targetService.protocol = "TCP";
            targetService.port = port;
            targetService.label = "SSH (" + port + ")";
            targetService.firstSeen = Instant.now();
            targetService.lastSeen = Instant.now();
            targetService.persist();
            device.services.add(targetService);
        }

        final NetworkService finalService = targetService;

        // Start SSH connection in a virtual thread
        log.infof("Starting SSH virtual thread for device %s (IP: %s)", deviceIdStr, ipAddress);
        Thread.startVirtualThread(() -> connectSsh(connection, ipAddress, cred, finalService));
    }

    private void connectSsh(WebSocketConnection connection, String ip, Credential cred, NetworkService service) {
        log.infof("Setting up SSH client for %s@%s", cred.username, ip);
        SshClient client = SshClient.setUpDefaultClient();
        
        // Trust On First Use (TOFU) logic
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
            String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
            log.infof("Received server key fingerprint: %s", fingerprint);

            if (service.sshHostKey == null || service.sshHostKey.isEmpty()) {
                // First use: store it but reject until trusted
                QuarkusTransaction.requiringNew().run(() -> {
                    NetworkService s = NetworkService.findById(service.id);
                    s.sshHostKey = fingerprint;
                    s.sshHostKeyTrusted = false;
                    s.persist();
                });
                
                connection.sendTextAndAwait("\r\n[Security] First time connecting to this host.\r\n");
                connection.sendTextAndAwait("[Security] Host Key Fingerprint: " + fingerprint + "\r\n");
                connection.sendTextAndAwait("[Security] Please explicitly trust this key in the UI before connecting.\r\n");
                return false;
            }

            if (!service.sshHostKey.equals(fingerprint)) {
                // Key changed! MITM or host re-installed
                connection.sendTextAndAwait("\r\n[CRITICAL WARNING] REMOTE HOST IDENTIFICATION HAS CHANGED!\r\n");
                connection.sendTextAndAwait("IT IS POSSIBLE THAT SOMEONE IS DOING SOMETHING NASTY!\r\n");
                connection.sendTextAndAwait("Someone could be eavesdropping on you right now (man-in-the-middle attack)!\r\n");
                connection.sendTextAndAwait("Expected: " + service.sshHostKey + "\r\n");
                connection.sendTextAndAwait("Received: " + fingerprint + "\r\n");
                return false;
            }

            if (service.sshHostKeyTrusted == null || !service.sshHostKeyTrusted) {
                connection.sendTextAndAwait("\r\n[Security] Host key is known but NOT TRUSTED.\r\n");
                connection.sendTextAndAwait("[Security] Please explicitly trust this key in the UI before connecting.\r\n");
                return false;
            }

            return true;
        });

        client.start();

        try {
            int port = cred.port != null ? cred.port : 22;
            log.infof("Connecting to %s@%s:%d", cred.username, ip, port);
            ClientSession session = client.connect(cred.username, ip, port).verify(10000).getSession();

            log.info("Reading secret from vault...");
            String secret = new String(vaultEngine.decrypt(cred.encryptedPayload, cred.noncePayload), StandardCharsets.UTF_8);
            
            if (cred.credentialType == CredentialType.PASSWORD) {
                log.info("Adding password identity");
                session.addPasswordIdentity(secret);
            } else if (cred.credentialType == CredentialType.SSH_KEY) {
                log.info("Adding SSH key identity (fallback password)");
                connection.sendTextAndAwait("Warning: Advanced SSH_KEY parsing might require more config. Trying password...\r\n");
                session.addPasswordIdentity(secret);
            }
            
            log.info("Authenticating session...");
            session.auth().verify(10000);
            log.info("Session authenticated successfully");

            log.info("Creating shell channel...");
            ChannelShell channel = session.createShellChannel();
            channel.setPtyType("xterm");
            channel.setPtyColumns(80);
            channel.setPtyLines(24);

            log.info("Setting up custom streams...");
            
            // Output from Server to WebSocket
            OutputStream wsOut = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (!connection.isClosed()) {
                        connection.sendTextAndAwait(new String(b, off, len, StandardCharsets.UTF_8));
                    }
                }
            };
            channel.setOut(wsOut);
            channel.setErr(wsOut);

            // Input from WebSocket to Server
            java.io.PipedOutputStream pout = new java.io.PipedOutputStream();
            java.io.PipedInputStream pin = new java.io.PipedInputStream(pout);
            channel.setIn(pin);

            log.info("Opening shell channel...");
            channel.open().verify(10000);
            log.info("Shell channel opened successfully");

            // Store session context for OnMessage
            activeSessions.put(connection.id(), new SshSessionContext(client, session, channel, pout));

            // Wait for channel to close
            log.info("Waiting for channel to close...");
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0);
            
            log.info("Channel closed");
            connection.sendTextAndAwait("\r\n[Connection Closed]\r\n");
            connection.close();

        } catch (Exception e) {
            log.error("SSH connection failed", e);
            connection.sendTextAndAwait("\r\nSSH Error: " + e.getMessage() + "\r\n");
            connection.close();
            cleanup(connection.id());
            try { client.stop(); } catch (Exception ignored) {}
        }
    }

    // We no longer need pipeStreamToWebSocket because we write directly to the WebSocket in the OutputStream

    @OnTextMessage
    public void onMessage(String message, WebSocketConnection connection) {
        SshSessionContext ctx = activeSessions.get(connection.id());
        if (ctx != null && ctx.out != null) {
            try {
                if (message.startsWith("{")) {
                    JsonNode node = mapper.readTree(message);
                    String type = node.path("type").asText("");
                    if ("resize".equals(type)) {
                        int cols = node.path("cols").asInt(80);
                        int rows = node.path("rows").asInt(24);
                        if (ctx.channel != null) {
                            ctx.channel.sendWindowChange(cols, rows, 0, 0);
                        }
                    } else if ("input".equals(type)) {
                        String data = node.path("data").asText("");
                        ctx.out.write(data.getBytes(StandardCharsets.UTF_8));
                        ctx.out.flush();
                    }
                } else {
                    ctx.out.write(message.getBytes(StandardCharsets.UTF_8));
                    ctx.out.flush();
                }
            } catch (Exception e) {
                log.error("Failed to write to SSH stream", e);
            }
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        cleanup(connection.id());
    }

    private void cleanup(String connectionId) {
        SshSessionContext ctx = activeSessions.remove(connectionId);
        if (ctx != null) {
            try { if (ctx.channel != null) ctx.channel.close(true); } catch (Exception ignored) {}
            try { if (ctx.session != null) ctx.session.close(true); } catch (Exception ignored) {}
            try { if (ctx.client != null) ctx.client.stop(); } catch (Exception ignored) {}
        }
    }

    private static class SshSessionContext {
        SshClient client;
        ClientSession session;
        ChannelShell channel;
        OutputStream out;

        public SshSessionContext(SshClient client, ClientSession session, ChannelShell channel, OutputStream out) {
            this.client = client;
            this.session = session;
            this.channel = channel;
            this.out = out;
        }
    }
}
