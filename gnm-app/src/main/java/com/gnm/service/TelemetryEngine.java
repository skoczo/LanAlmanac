package com.gnm.service;

import com.gnm.model.Credential;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.Telemetry;
import com.gnm.model.enums.CredentialType;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

@ApplicationScoped
public class TelemetryEngine {

    private static final Logger log = Logger.getLogger(TelemetryEngine.class);

    @Inject
    VaultEngine vaultEngine;

    @Scheduled(every = "60s")
    @Transactional
    public void pollMetrics() {
        if (!vaultEngine.isUnsealed()) {
            log.debug("Vault is sealed, skipping telemetry polling");
            return;
        }

        List<PhysicalDevice> devices = PhysicalDevice.listAll();
        for (PhysicalDevice device : devices) {
            String ipAddress = device.identities.stream()
                    .filter(id -> id.current)
                    .map(id -> id.ipAddress)
                    .findFirst()
                    .orElse(null);

            if (ipAddress == null) continue;

            Credential cred = device.credentials.stream().findFirst().orElse(null);
            if (cred == null) continue;

            try {
                if (cred.credentialType == CredentialType.PASSWORD || cred.credentialType == CredentialType.SSH_KEY) {
                    pollSshMetrics(device, ipAddress, cred);
                } else if (cred.credentialType == CredentialType.SNMP) {
                    pollSnmpMetrics(device, ipAddress, cred);
                }
            } catch (Exception e) {
                log.warn("Failed to poll metrics for device " + device.id, e);
            }
        }
    }

    private void pollSshMetrics(PhysicalDevice device, String ipAddress, Credential cred) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();
        client.start();

        try {
            int port = cred.port != null ? cred.port : 22;
            ClientSession session = client.connect(cred.username, ipAddress, port).verify(10000).getSession();

            String secret = new String(vaultEngine.decrypt(cred.encryptedPayload, cred.noncePayload), StandardCharsets.UTF_8);
            session.addPasswordIdentity(secret);
            session.auth().verify(10000);

            // Poll CPU Load (1 min average)
            String loadavg = executeSshCommand(session, "cat /proc/loadavg");
            if (loadavg != null && !loadavg.isEmpty()) {
                String[] parts = loadavg.trim().split("\\s+");
                if (parts.length > 0) {
                    saveMetric(device, "cpu_load_1m", Double.parseDouble(parts[0]));
                }
            }

            // Poll RAM usage %
            String freeOut = executeSshCommand(session, "free | grep Mem");
            if (freeOut != null && !freeOut.isEmpty()) {
                String[] parts = freeOut.trim().split("\\s+");
                if (parts.length >= 3) {
                    double total = Double.parseDouble(parts[1]);
                    double used = Double.parseDouble(parts[2]);
                    saveMetric(device, "ram_usage_percent", (used / total) * 100.0);
                }
            }

            session.close(false);
        } finally {
            client.stop();
        }
    }

    private String executeSshCommand(ClientSession session, String command) throws Exception {
        ClientChannel channel = session.createExecChannel(command);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        channel.setOut(out);
        channel.open().verify(5000);
        channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 5000);
        channel.close(false);
        return out.toString(StandardCharsets.UTF_8);
    }

    private void pollSnmpMetrics(PhysicalDevice device, String ipAddress, Credential cred) {
        // Simplified SNMP polling (in a real scenario, this would use org.snmp4j to query OIDs)
        // For demonstration of the pipeline, we generate simulated metrics if SNMP is configured.
        log.info("Simulating SNMP metrics for " + ipAddress);
        saveMetric(device, "cpu_load_1m", Math.random() * 2.0);
        saveMetric(device, "ram_usage_percent", 30.0 + (Math.random() * 40.0));
    }

    private void saveMetric(PhysicalDevice device, String metricName, double value) {
        Telemetry t = new Telemetry();
        t.id = new Telemetry.TelemetryId(Instant.now(), device.id, metricName);
        t.value = value;
        t.persist();
    }
}
