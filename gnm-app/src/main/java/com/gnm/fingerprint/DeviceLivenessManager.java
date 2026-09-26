package com.gnm.fingerprint;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.gnm.model.PhysicalDevice;
import com.gnm.model.GlobalSetting;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.discovery.IcmpSweeper;

@ApplicationScoped
public class DeviceLivenessManager {
    private static final Logger LOG = Logger.getLogger(DeviceLivenessManager.class);
    
    @Inject Event<FingerprintEngine.DeviceEvent> eventBroadcaster;
    @Inject Event<com.gnm.resource.EventWebSocket.DiscoveryActivityEvent> activityBroadcaster;
    @Inject IcmpSweeper icmpSweeper;
    @Inject DeviceLivenessManager self;

    // Track the exact last time an IP was seen (by passive sniffer, DHCP, manual scans, etc.)
    private final Map<String, Instant> lastSeenMap = new ConcurrentHashMap<>();
    // Track when we last performed an active targeted check for a silent device
    private final Map<String, Instant> lastActiveCheckMap = new ConcurrentHashMap<>();

    public void recordActivity(String ip) {
        if (ip == null || ip.isBlank() || "0.0.0.0".equals(ip) || "255.255.255.255".equals(ip)) return;
        lastSeenMap.put(ip, Instant.now());
        activityBroadcaster.fireAsync(new com.gnm.resource.EventWebSocket.DiscoveryActivityEvent(
            "PASSIVE_HEARTBEAT", ip, "Passive network activity detected", null));
    }

    public Instant getLastSeen(String ip) {
        return lastSeenMap.get(ip);
    }

    public void clearCache() {
        lastSeenMap.clear();
        lastActiveCheckMap.clear();
    }

    @Scheduled(every = "15s", identity = "smart-presence-job")
    public void evaluatePresenceScheduled() {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST) {
            return;
        }
        evaluatePresence();
    }

    public void evaluatePresence() {

        GlobalSetting setting = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> GlobalSetting.findById("ENABLE_ACTIVE_SCANNING"));
        if (setting != null && "false".equalsIgnoreCase(setting.value)) {
            return; // Smart presence requires active checking when silent
        }

        int activeCheckSeconds = 240; // 4 minutes silence before checking
        int offlineSeconds = 300;     // 5 minutes silence before OFFLINE
        
        try {
            GlobalSetting offlineSetting = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> GlobalSetting.findById("DEVICE_OFFLINE_MISSED_PROBES_THRESHOLD"));
            if (offlineSetting != null) {
                try {
                    int missed = Integer.parseInt(offlineSetting.value);
                    offlineSeconds = missed * 60;
                    activeCheckSeconds = Math.max(60, offlineSeconds - 60); // Check 1 min before it goes offline
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        List<PhysicalDevice> allDevices;
        try {
            allDevices = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                .call(() -> PhysicalDevice.find("SELECT distinct p FROM PhysicalDevice p LEFT JOIN FETCH p.identities").list());
        } catch (Exception e) {
            LOG.error("Failed to list devices for smart presence evaluation", e);
            return;
        }

        Instant now = Instant.now();
        for (PhysicalDevice device : allDevices) {
            processDevicePresence(device, now, activeCheckSeconds, offlineSeconds);
        }
    }

    private void processDevicePresence(PhysicalDevice device, Instant now, int activeCheckSeconds, int offlineSeconds) {
        // Collect IPs
        List<String> deviceIps = device.identities.stream()
            .map(id -> id.ipAddress)
            .filter(ip -> ip != null && !ip.isBlank())
            .distinct()
            .toList();

        if (deviceIps.isEmpty()) return;

        String currentIp = device.identities.stream()
            .filter(id -> id.current)
            .map(id -> id.ipAddress)
            .findFirst()
            .orElse(deviceIps.get(0));

        // Mobile phones get longer timeouts (usually sleep longer to save battery)
        int effOfflineSeconds = offlineSeconds;
        int effActiveCheckSeconds = activeCheckSeconds;
        if (device.deviceType == com.gnm.model.enums.DeviceType.PHONE) {
            effOfflineSeconds = Math.max(offlineSeconds, 600); // at least 10 minutes
            effActiveCheckSeconds = Math.max(activeCheckSeconds, 480);
        }

        Instant latestSeen = device.lastSeen;
        for (String ip : deviceIps) {
            Instant memSeen = lastSeenMap.get(ip);
            if (memSeen != null && (latestSeen == null || memSeen.isAfter(latestSeen))) {
                latestSeen = memSeen;
            }
        }

        // If we haven't seen it in memory, fallback to DB lastSeen
        if (latestSeen == null) return;

        long silenceSeconds = java.time.Duration.between(latestSeen, now).getSeconds();
        LOG.infof("SmartPresence: Evaluating %s, silenceSeconds=%d, effActiveCheck=%d, effOffline=%d", currentIp, silenceSeconds, effActiveCheckSeconds, effOfflineSeconds);

        if (silenceSeconds <= effActiveCheckSeconds) {
            // Device is actively talking on the network (passive sniffer saw it recently).
            // Ensure it is marked ONLINE.
            if (device.status != DeviceStatus.ONLINE || (device.consecutiveMissedProbes > 0)) {
                self.markOnline(device.id, currentIp);
            }
            return;
        }

        // Device is silent. We need to check if it's dead or just quiet.
        if (silenceSeconds > effOfflineSeconds) {
            LOG.infof("SmartPresence: %s silence > offline. Marking offline directly.", currentIp);
            if (device.status != DeviceStatus.OFFLINE) {
                self.markOffline(device.id, currentIp);
            }
            return;
        }

        // Device is in the "warning" zone. It's silent for longer than ACTIVE_CHECK_THRESHOLD but not yet OFFLINE.
        // We should trigger an active ICMP/ARP check for it.
        // Throttle active checks to avoid hammering it every 15 seconds.
        Instant lastCheck = lastActiveCheckMap.get(currentIp);
        if (lastCheck == null || java.time.Duration.between(lastCheck, now).getSeconds() > 60) {
            lastActiveCheckMap.put(currentIp, now);
            
            activityBroadcaster.fireAsync(new com.gnm.resource.EventWebSocket.DiscoveryActivityEvent(
                "TARGETED_SCAN", currentIp, "Silent for " + silenceSeconds + "s, verifying liveness...", device.displayName));

            // Run the targeted check asynchronously using Virtual Threads
            Thread.ofVirtual().start(() -> {
                boolean reachable = icmpSweeper.checkTargetReachable(currentIp);
                if (reachable) {
                    LOG.debugf("SmartPresence: %s was silent, but responded to targeted check. Resetting lastSeen.", currentIp);
                    recordActivity(currentIp);
                    self.markOnline(device.id, currentIp);
                    activityBroadcaster.fireAsync(new com.gnm.resource.EventWebSocket.DiscoveryActivityEvent(
                        "TARGETED_SCAN_RESULT", currentIp, "Responded to active scan", device.displayName));
                } else {
                    LOG.debugf("SmartPresence: %s targeted check failed. Device remains silent.", currentIp);
                    activityBroadcaster.fireAsync(new com.gnm.resource.EventWebSocket.DiscoveryActivityEvent(
                        "TARGETED_SCAN_RESULT", currentIp, "Did not respond to active scan", device.displayName));
                }
            });
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markOnline(java.util.UUID deviceId, String currentIp) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device != null) {
            boolean statusChanged = (device.status != DeviceStatus.ONLINE);
            device.status = DeviceStatus.ONLINE;
            device.consecutiveMissedProbes = 0;
            device.lastSeen = Instant.now();
            device.persist();
            if (statusChanged) {
                eventBroadcaster.fireAsync(new FingerprintEngine.DeviceEvent("ONLINE", device.id.toString(), device.displayName, "ONLINE", currentIp));
            }
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markOffline(java.util.UUID deviceId, String currentIp) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device != null && device.status != DeviceStatus.OFFLINE) {
            device.status = DeviceStatus.OFFLINE;
            device.consecutiveMissedProbes = 5; // To match existing logic expectations
            device.persist();
            eventBroadcaster.fireAsync(new FingerprintEngine.DeviceEvent("STATUS_CHANGE", device.id.toString(), device.displayName, "OFFLINE", currentIp));
            LOG.infof("SmartPresence: Marked device %s as OFFLINE after prolonged silence.", device.displayName);
        }
    }
}
