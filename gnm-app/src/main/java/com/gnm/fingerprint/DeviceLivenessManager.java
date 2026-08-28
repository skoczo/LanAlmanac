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

    public void updateProbeCounterInTransaction(UUID deviceId, Set<String> liveIps, int threshold) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) return;

        // Determine the device's current IP address
        String currentIp = device.identities.stream()
            .filter(id -> id.current)
            .map(id -> id.ipAddress)
            .findFirst()
            .orElse(null);

        int effectiveThreshold = threshold;
        int passiveWindowSeconds = 180;

        if (device.deviceType == com.gnm.model.enums.DeviceType.PHONE) {
            effectiveThreshold = Math.max(threshold, 10); // 10 missed cycles (10 minutes) for mobile devices in power-save mode
            passiveWindowSeconds = 600; // 10 minutes passive window
        }

        boolean seenInThisCycle = currentIp != null && liveIps.contains(currentIp);

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
                eventBroadcaster.fireAsync(new DeviceEvent("ONLINE", device.id.toString(), device.displayName, "ONLINE", currentIp));
            }
            device.persist();
        } else {
            if (device.status == DeviceStatus.OFFLINE) {
                return; // Already offline, no need to do fallback ping or increment counter
            }

            // Double-check liveness before penalizing, in case it was missed by the sweep
            boolean fallbackReachable = false;
            if (currentIp != null) {
                Process p = null;
                try {
                    p = new ProcessBuilder("ping", "-n", "-c", "1", "-W", "1", currentIp).start();
                    boolean finished = p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (finished) {
                        fallbackReachable = (p.exitValue() == 0);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (p != null) {
                        p.destroyForcibly();
                    }
                }
            }
            if (fallbackReachable) {
                LOG.debugf("Device %s (IP: %s) missed primary sweep but responded to fallback ping. Resetting counter.", device.displayName, currentIp);
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
                    eventBroadcaster.fireAsync(new DeviceEvent("STATUS_CHANGE", device.id.toString(), device.displayName, "OFFLINE", ip));
                    LOG.infof("Marked device %s as OFFLINE after %d consecutive missed ICMP probe cycles.",
                        device.displayName, device.consecutiveMissedProbes);
                } else {
                    device.persist();
                }
            }
        }
    }
}
