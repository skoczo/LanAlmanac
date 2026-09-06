package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSH Public Host Key Fingerprint Probe.
 * <p>
 * Connects to open SSH ports (22, 2222, 2223, 2224) using Apache Mina SSHD.
 * Intercepts the server's public key during key exchange (KEX) using a custom {@code ServerKeyVerifier},
 * computes the SHA-256 host key fingerprint, and appends it to {@code candidate.sshHostKeys}.
 * Priority 20 (runs early to harvest unique structural device signatures).
 */
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
    public boolean isHostnameProbe() {
        return false;
    }


    @Override
    public void execute(ProbeContext context) throws Exception {
        if (context.getOpenPorts().isEmpty()) return;
        // Probe only ports recognized as potential SSH service ports
        for (Integer port : context.getOpenPorts()) {
            if (port == 22 || port == 2222 || port == 2223 || port == 2224) {
                AtomicReference<String> hostKeyRef = new AtomicReference<>();
                try (SshClient client = SshClient.setUpDefaultClient()) {
                    // Register custom verifier to capture server key fingerprint during handshake without authenticating
                    client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
                        String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
                        hostKeyRef.set(fingerprint);
                        return false; // Intentionally abort session after capturing server public key
                    });
                    client.start();
                    try (ClientSession session = client.connect("fakeuser", context.getIpAddress(), port).verify(2000).getSession()) {
                        session.auth().verify(2000); 
                    } catch (Exception e) {
                        // Exception expected when verifier rejects connection after capturing key
                    }
                }
                String key = hostKeyRef.get();
                if (key != null && !key.isEmpty()) {
                    context.getCandidate().sshHostKeys.add(key);
                }
            }
        }
    }

}

