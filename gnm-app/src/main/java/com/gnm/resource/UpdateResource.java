package com.gnm.resource;

import com.gnm.model.Credential;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.CredentialType;
import com.gnm.service.VaultEngine;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.UUID;

@Path("/api/devices")
public class UpdateResource {

    private static final Logger log = Logger.getLogger(UpdateResource.class);

    @Inject
    VaultEngine vaultEngine;

    @Inject
    ManagedExecutor executor;

    @GET
    @Path("/{id}/update")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Transactional
    public void streamUpdate(@PathParam("id") UUID id, @Context SseEventSink sink, @Context Sse sse) {
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
                .filter(c -> c.credentialType == CredentialType.PASSWORD || c.credentialType == CredentialType.SSH_KEY)
                .findFirst()
                .orElse(null);

        if (cred == null) {
            sink.send(sse.newEvent("Error: No SSH credentials found for device."));
            sink.close();
            return;
        }

        executor.submit(() -> executeUpdate(ipAddress, cred, sink, sse));
    }

    private void executeUpdate(String ip, Credential cred, SseEventSink sink, Sse sse) {
        SshClient client = SshClient.setUpDefaultClient();
        client.start();

        try {
            int port = cred.port != null ? cred.port : 22;
            ClientSession session = client.connect(cred.username, ip, port).verify(10000).getSession();

            String secret = new String(vaultEngine.decrypt(cred.encryptedPayload, cred.noncePayload), StandardCharsets.UTF_8);
            session.addPasswordIdentity(secret);
            session.auth().verify(10000);

            String command = "sudo apt-get update && sudo apt-get upgrade -y";
            ChannelExec channel = session.createExecChannel(command);

            InputStream in = channel.getInvertedOut();
            InputStream err = channel.getInvertedErr();

            channel.open().verify(5000);

            byte[] buffer = new byte[1024];
            int i;
            while ((i = in.read(buffer)) != -1) {
                String out = new String(buffer, 0, i, StandardCharsets.UTF_8);
                sink.send(sse.newEvent(out.replace("\n", "\\n")));
            }

            while ((i = err.read(buffer)) != -1) {
                String out = new String(buffer, 0, i, StandardCharsets.UTF_8);
                sink.send(sse.newEvent("ERROR: " + out.replace("\n", "\\n")));
            }

            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 0);
            sink.send(sse.newEvent("Update Complete."));
            channel.close(false);
            session.close(false);

        } catch (Exception e) {
            log.error("Update failed", e);
            sink.send(sse.newEvent("Update Failed: " + e.getMessage()));
        } finally {
            sink.close();
            try { client.stop(); } catch (Exception ignored) {}
        }
    }
}
