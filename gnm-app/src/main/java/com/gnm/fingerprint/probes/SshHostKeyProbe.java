package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class SshHostKeyProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(SshHostKeyProbe.class);

    @Override
    public int getTimeoutMs() {
        return 2000;
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public void execute(ProbeContext context) {
        if (context.getOpenPorts().isEmpty()) return;
        for (Integer port : context.getOpenPorts()) {
            if (port == 22 || port == 2222 || port == 2223 || port == 2224) {
                AtomicReference<String> hostKeyRef = new AtomicReference<>();
                try (SshClient client = SshClient.setUpDefaultClient()) {
                    client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
                        String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
                        hostKeyRef.set(fingerprint);
                        return false;
                    });
                    client.start();
                    try (ClientSession session = client.connect("fakeuser", context.getIpAddress(), port).verify(2000).getSession()) {
                        session.auth().verify(2000); 
                    } catch (Exception e) {}
                } catch (Exception e) {
                    LOG.error("Failed to fetch SSH host key", e);
                }
                String key = hostKeyRef.get();
                if (key != null && !key.isEmpty()) {
                    context.getCandidate().sshHostKeys.add(key);
                }
            }
        }
    }
}
