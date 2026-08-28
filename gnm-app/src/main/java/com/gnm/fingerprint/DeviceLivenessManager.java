package com.gnm.fingerprint;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.gnm.model.*;
import com.gnm.model.enums.*;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;

@ApplicationScoped
public class DeviceLivenessManager {
    private static final Logger LOG = Logger.getLogger(DeviceLivenessManager.class);
    @Inject Event<FingerprintEngine.DeviceEvent> eventBroadcaster;
    @Inject DeviceLivenessManager self;

    public void updateProbeCounters(Set<String> liveIps) {
        int threshold = 2;
        try {
            GlobalSetting setting = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> GlobalSetting.findById("DEVICE_OFFLINE_MISSED_PROBES_THRESHOLD"));
            if (setting != null) {
                try { threshold = Integer.parseInt(setting.value); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        final int finalThreshold = threshold;

        List<PhysicalDevice> allDevices;
        try {
            allDevices = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> PhysicalDevice.listAll());
        } catch (Exception e) {
            LOG.error("Failed to list devices for probe counter update", e);
            return;
        }

        for (PhysicalDevice device : allDevices) {
            try {
                self.updateProbeCounterInTransaction(device.id, liveIps, finalThreshold);
            } catch (Exception e) {
                LOG.error("Failed to update probe counter for device " + device.id, e);
            }
        }
    }

    @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
    public void updateProbeCounterInTransaction(UUID deviceId, Set<String> liveIps, int threshold) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) return;

        // Collect all IP addresses associated with this device
        List<String> deviceIps = device.identities.stream()
            .map(id -> id.ipAddress)
            .filter(ip -> ip != null && !ip.isBlank())
            .distinct()
            .toList();

        String currentIp = device.identities.stream()
            .filter(id -> id.current)
            .map(id -> id.ipAddress)
            .findFirst()
            .orElse(deviceIps.isEmpty() ? null : deviceIps.get(0));

        int effectiveThreshold = threshold > 0 ? threshold : 2;
        int passiveWindowSeconds = 180;

        if (device.deviceType == com.gnm.model.enums.DeviceType.PHONE) {
            effectiveThreshold = Math.max(threshold, 10); // 10 missed cycles (10 minutes) for mobile devices in power-save mode
            passiveWindowSeconds = 600; // 10 minutes passive window
        }

        boolean seenInThisCycle = deviceIps.stream().anyMatch(liveIps::contains);

        if (!seenInThisCycle && device.lastSeen != null) {
            // Check if we have seen this device recently (e.g. passively via ARP or UDP broadcasts)
            if (device.lastSeen.isAfter(Instant.now().minusSeconds(passiveWindowSeconds))) {
                seenInThisCycle = true;
                LOG.debugf("Device %s missed active probe but was seen passively recently at %s.", device.displayName, device.lastSeen);
            }
        }

        if (seenInThisCycle) {
            // Device responded — reset the counter and bring online if offline
            if (device.consecutiveMissedProbes > 0) {
                device.consecutiveMissedProbes = 0;
            }
            if (device.status != DeviceStatus.ONLINE) {
                device.status = DeviceStatus.ONLINE;
                device.lastSeen = Instant.now();
                eventBroadcaster.fireAsync(new FingerprintEngine.DeviceEvent("ONLINE", device.id.toString(), device.displayName, "ONLINE", currentIp != null ? currentIp : "0.0.0.0"));
            }
            device.persist();
        } else {
            if (device.status == DeviceStatus.OFFLINE) {
                return; // Already offline, no need to do fallback ping or increment counter
            }

            // Double-check liveness before penalizing, in case it was missed by the sweep
            boolean fallbackReachable = false;
            for (String ip : deviceIps) {
                if (isHostFallbackReachable(ip, device)) {
                    fallbackReachable = true;
                    break;
                }
            }

            if (fallbackReachable) {
                LOG.debugf("Device %s (IP: %s) missed primary sweep but responded to fallback liveness check. Resetting counter.", device.displayName, currentIp);
                if (device.consecutiveMissedProbes > 0) {
                    device.consecutiveMissedProbes = 0;
                    device.persist();
                }
            } else {
                // Device did NOT respond in this sweep cycle and fallback failed
                device.consecutiveMissedProbes++;
                LOG.debugf("Device %s (IP: %s) missed probe cycle. consecutiveMissedProbes=%d (threshold=%d)",
                    device.displayName, currentIp, device.consecutiveMissedProbes, effectiveThreshold);

                if (device.consecutiveMissedProbes >= effectiveThreshold) {
                    device.status = DeviceStatus.OFFLINE;
                    device.persist();
                    String ip = currentIp != null ? currentIp : "0.0.0.0";
                    eventBroadcaster.fireAsync(new FingerprintEngine.DeviceEvent("STATUS_CHANGE", device.id.toString(), device.displayName, "OFFLINE", ip));
                    LOG.infof("Marked device %s as OFFLINE after %d consecutive missed ICMP probe cycles.",
                        device.displayName, device.consecutiveMissedProbes);
                } else {
                    device.persist();
                }
            }
        }
    }

    private boolean isHostFallbackReachable(String ip, PhysicalDevice device) {
        // 1. Try quick system ICMP ping
        Process p = null;
        try {
            p = new ProcessBuilder("ping", "-n", "-c", "1", "-W", "1", ip).start();
            boolean finished = p.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (finished && p.exitValue() == 0) {
                return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroyForcibly();
        }

        // 2. Try TCP connect to registered service ports or common fallback ports
        List<Integer> portsToTry = new ArrayList<>();
        if (device.services != null) {
            for (NetworkService s : device.services) {
                if (s.port != null && s.port > 0) {
                    portsToTry.add(s.port);
                }
            }
        }
        portsToTry.addAll(List.of(22, 80, 443, 445, 1883, 3000, 5000, 7125, 8000, 8006, 8080, 8123, 8443, 9000, 9443));

        for (int port : portsToTry) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(ip, port), 200);
                return true;
            } catch (java.io.IOException e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("refused")) {
                    return true;
                }
            }
        }

        // 3. Check system ARP cache (/proc/net/arp) directly
        try {
            java.io.File arpFile = new java.io.File("/proc/net/arp");
            if (arpFile.exists() && arpFile.canRead()) {
                List<String> lines = java.nio.file.Files.readAllLines(arpFile.toPath());
                for (int i = 1; i < lines.size(); i++) {
                    String[] parts = lines.get(i).trim().split("\\s+");
                    if (parts.length >= 4 && ip.equals(parts[0])) {
                        String flags = parts[2];
                        String mac = parts[3];
                        if (!"00:00:00:00:00:00".equals(mac) && !"0x0".equals(flags)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return false;
    }
}
