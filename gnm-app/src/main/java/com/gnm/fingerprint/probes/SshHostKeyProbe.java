package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import java.util.List;
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
        List<Integer> portsToTry = context.getOpenPorts().isEmpty() ? new java.util.ArrayList<>(List.of(22)) : new java.util.ArrayList<>(context.getOpenPorts());
        
        String targetHost = context.getIpAddress();
        if (Boolean.getBoolean("forceNetworkScan") && System.getProperty("test.ssh.host") != null) {
            targetHost = System.getProperty("test.ssh.host");
        }
        if (System.getProperty("test.ssh.port") != null) {
            try {
                int testPort = Integer.parseInt(System.getProperty("test.ssh.port"));
                if (!portsToTry.contains(testPort)) {
                    portsToTry.add(0, testPort);
                }
            } catch (Exception ignored) {}
        }
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST && "172.17.0.1".equals(targetHost)) {
            targetHost = "127.0.0.1";
        }

        for (Integer port : portsToTry) {
            if (port == 22 || port == 2222 || port == 2223 || port == 2224 || Boolean.getBoolean("forceNetworkScan")) {
                AtomicReference<String> hostKeyRef = new AtomicReference<>();
                try (SshClient client = SshClient.setUpDefaultClient()) {
                    // Register custom verifier to capture server key fingerprint during handshake without authenticating
                    client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
                        String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
                        hostKeyRef.set(fingerprint);
                        return false; // Intentionally abort session after capturing server public key
                    });
                    client.start();
                    try (ClientSession session = client.connect("fakeuser", targetHost, port).verify(2000).getSession()) {
                        session.auth().verify(2000); 
                    } catch (Exception e) {
                        if (e instanceof java.net.ConnectException || 
                            e instanceof java.net.SocketTimeoutException || 
                            e instanceof java.net.NoRouteToHostException) {
                            LOG.debugf("No SSH service or unreachable on port %d: %s", port, e.getMessage());
                        } else if (e instanceof org.apache.sshd.common.SshException && e.getMessage() != null && e.getMessage().contains("Server key did not validate")) {
                            // Exception expected when verifier rejects connection after capturing key
                            LOG.debugf("Captured SSH key and intentionally aborted connection on port %d", port);
                        } else {
                            // We shouldn't swallow other unexpected exceptions (e.g. Crypto/BouncyCastle issues)
                            LOG.warnf("Unexpected exception during SSH probe on port %d: %s", port, e.getMessage(), e);
                        }
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

