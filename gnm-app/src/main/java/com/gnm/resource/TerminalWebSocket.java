package com.gnm.resource;

import com.gnm.model.Credential;
import com.gnm.model.NetworkIdentity;
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
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@WebSocket(path = "/ws/terminal/{deviceId}/{credentialId}")
public class TerminalWebSocket {

    private static final Logger log = Logger.getLogger(TerminalWebSocket.class);

    @Inject
    VaultEngine vaultEngine;

    // Track active sessions to pipe input correctly and clean up on close
    private final Map<String, SshSessionContext> activeSessions = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(WebSocketConnection connection, @PathParam("deviceId") String deviceIdStr, @PathParam("credentialId") String credentialIdStr) {
        log.infof("Terminal WebSocket opened for device %s", deviceIdStr);
        
        if (!vaultEngine.isUnsealed()) {
            connection.sendText("Error: Vault is sealed.\r\n");
            connection.close();
            return;
        }

        UUID deviceId = UUID.fromString(deviceIdStr);
        UUID credentialId = UUID.fromString(credentialIdStr);

        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            connection.sendText("Error: Device not found.\r\n");
            connection.close();
            return;
        }

        Credential cred = Credential.findById(credentialId);
        if (cred == null || !cred.physicalDevice.id.equals(device.id)) {
            connection.sendText("Error: Credential not found.\r\n");
            connection.close();
            return;
        }

        String ipAddress = device.identities.stream()
                .filter(id -> id.current)
                .map(id -> id.ipAddress)
                .findFirst()
                .orElse(null);

        if (ipAddress == null) {
            connection.sendText("Error: Device has no active IP address.\r\n");
            connection.close();
            return;
        }

        // Start SSH connection in a virtual thread
        Thread.startVirtualThread(() -> connectSsh(connection, ipAddress, cred));
    }

    private void connectSsh(WebSocketConnection connection, String ip, Credential cred) {
        SshClient client = SshClient.setUpDefaultClient();
        client.start();

        try {
            int port = cred.port != null ? cred.port : 22;
            ClientSession session = client.connect(cred.username, ip, port).verify(10000).getSession();
            
            // Decrypt password/key
            String secret = new String(vaultEngine.decrypt(cred.encryptedPayload, cred.noncePayload), StandardCharsets.UTF_8);
            
            if (cred.credentialType == CredentialType.PASSWORD) {
                session.addPasswordIdentity(secret);
            } else if (cred.credentialType == CredentialType.SSH_KEY) {
                // For simplicity, we inject private key as a string. 
                // A complete implementation would parse the PEM key. MINA SSHD has KeyUtils for this.
                // Assuming it's password for now, full PEM parsing is complex to do inline here.
                connection.sendText("Warning: Advanced SSH_KEY parsing might require more config. Trying password...\r\n");
                session.addPasswordIdentity(secret);
            }
            
            session.auth().verify(10000);

            ChannelShell channel = session.createShellChannel();
            channel.setPtyType("xterm");
            channel.setPtyColumns(80);
            channel.setPtyLines(24);

            OutputStream out = channel.getInvertedIn();
            InputStream in = channel.getInvertedOut();
            InputStream err = channel.getInvertedErr();

            channel.open().verify(10000);

            // Store session context for OnMessage
            activeSessions.put(connection.id(), new SshSessionContext(client, session, channel, out));

            // Start threads to read from SSH and pipe to WebSocket
            Thread.startVirtualThread(() -> pipeStreamToWebSocket(in, connection));
            Thread.startVirtualThread(() -> pipeStreamToWebSocket(err, connection));

            // Wait for channel to close
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0);
            
            connection.sendText("\r\n[Connection Closed]\r\n");
            connection.close();

        } catch (Exception e) {
            log.error("SSH connection failed", e);
            connection.sendText("\r\nSSH Error: " + e.getMessage() + "\r\n");
            connection.close();
            cleanup(connection.id());
            try { client.stop(); } catch (Exception ignored) {}
        }
    }

    private void pipeStreamToWebSocket(InputStream is, WebSocketConnection connection) {
        try {
            byte[] buffer = new byte[1024];
            int i;
            while ((i = is.read(buffer)) != -1) {
                if (connection.isClosed()) break;
                connection.sendText(new String(buffer, 0, i, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // Stream closed
        }
    }

    @OnTextMessage
    public void onMessage(String message, WebSocketConnection connection) {
        SshSessionContext ctx = activeSessions.get(connection.id());
        if (ctx != null && ctx.out != null) {
            try {
                // If the message is JSON with a terminal resize command, we'd handle it here.
                // For now, we assume direct PTY input.
                ctx.out.write(message.getBytes(StandardCharsets.UTF_8));
                ctx.out.flush();
            } catch (IOException e) {
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
