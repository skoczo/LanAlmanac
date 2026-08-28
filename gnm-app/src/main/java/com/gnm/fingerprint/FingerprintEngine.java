package com.gnm.fingerprint;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.gnm.discovery.NetworkSightingQueue;
import com.gnm.model.*;
import com.gnm.model.enums.*;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.inject.Instance;
import com.gnm.fingerprint.probes.NetworkProbe;
import com.gnm.fingerprint.probes.ProbeContext;

@ApplicationScoped
public class FingerprintEngine {

    private static final Logger LOG = Logger.getLogger(FingerprintEngine.class);
    private final ObjectMapper MAPPER = new ObjectMapper();
    private final java.util.concurrent.locks.ReentrantLock dbLock = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.Map<String, java.time.Instant> lastScanTimes = new java.util.concurrent.ConcurrentHashMap<>();

    // Limits max concurrent network scans globally
    private final java.util.concurrent.Semaphore activeScanConcurrency = new java.util.concurrent.Semaphore(5);

    @Inject
    Instance<NetworkProbe> networkProbes;

    private List<NetworkProbe> sortedProbes = new ArrayList<>();
    private int dynamicTimeoutMs = 60000; // default fallback

    private volatile boolean running = true;
    private java.util.concurrent.ExecutorService executorService;
    private java.util.concurrent.ScheduledExecutorService timeoutScheduler;
    private Thread pollingThread;

    @Inject NetworkSightingQueue sightingQueue;
    @Inject SimilarityEngine similarityEngine;
    @Inject DeviceIdentityManager identityManager;
    @Inject DeviceLivenessManager livenessManager;

    private final java.util.concurrent.atomic.AtomicInteger activeProcessingCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.Map<String, java.time.Instant> lastDbUpdateTimes = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject
    Event<DeviceEvent> eventBroadcaster;

    @Inject
    Event<ThreatEvent> threatBroadcaster;

    @Inject
    FingerprintEngine self;

    @ConfigProperty(name = "gnm.fingerprint.merge-threshold", defaultValue = "0.75")
    Double mergeThreshold;

    public static class DeviceEvent {
        public String type; // "NEW_DEVICE", "STATUS_CHANGE"
        public String deviceId;
        public String displayName;
        public String status;
        public String ipAddress;

        public DeviceEvent(String type, String deviceId, String displayName, String status, String ipAddress) {
            this.type = type;
            this.deviceId = deviceId;
            this.displayName = displayName;
            this.status = status;
            this.ipAddress = ipAddress;
        }
    }

    public void start(@Observes StartupEvent ev) {
        LOG.info("Starting background Fingerprint Processing Engine with ThreadPoolExecutor...");
        executorService = new java.util.concurrent.ThreadPoolExecutor(
                20, 20, 
                0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(1000),
                java.util.concurrent.Executors.defaultThreadFactory(),
                new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
        );
        timeoutScheduler = java.util.concurrent.Executors.newScheduledThreadPool(1);
        pollingThread = new Thread(this::runProcessingLoop);
        pollingThread.setName("SightingQueue-Poller");
        pollingThread.start();
        
        // Setup dynamic probes
        for (NetworkProbe probe : networkProbes) {
            sortedProbes.add(probe);
        }
        sortedProbes.sort(java.util.Comparator.comparingInt(NetworkProbe::getPriority));
        dynamicTimeoutMs = sortedProbes.stream().mapToInt(NetworkProbe::getTimeoutMs).sum() + 2000; // +2s buffer
        LOG.info("Configured " + sortedProbes.size() + " dynamic probes. Total max timeout calculated as: " + dynamicTimeoutMs + "ms");
    }

    public void stop(@Observes ShutdownEvent ev) {
        LOG.info("Stopping Fingerprint Processing Engine...");
        running = false;
        if (pollingThread != null) pollingThread.interrupt();
        if (executorService != null) executorService.shutdownNow();
        if (timeoutScheduler != null) timeoutScheduler.shutdownNow();
    }

    private void runProcessingLoop() {
        while (running) {
            try {
                NetworkSighting sighting = sightingQueue.take();
                
                String debounceKey = sighting.ipAddress + "|" + sighting.macAddress;
                java.time.Instant lastDbUpdate = lastDbUpdateTimes.get(debounceKey);
                boolean isArpScanOnly = sighting.rawMetadata != null
                    && sighting.rawMetadata.contains("\"flags\":")
                    && !sighting.rawMetadata.contains("\"host\"")
                    && !sighting.rawMetadata.contains("\"dhcp\"")
                    && !sighting.rawMetadata.contains("\"mdns\"");
                boolean hasRawMetadata = !isArpScanOnly && sighting.rawMetadata != null
                    && !sighting.rawMetadata.equals("{}")
                    && !sighting.rawMetadata.isEmpty()
                    && !sighting.rawMetadata.equals("{\"protocol\":\"arp\"}");
                boolean isIcmpSweep = "ICMP_SWEEP".equals(sighting.source);
                boolean isManual = "MANUAL_DISCOVERY".equals(sighting.source);
                
                if (!hasRawMetadata && !isIcmpSweep && !isManual && lastDbUpdate != null && java.time.Instant.now().isBefore(lastDbUpdate.plusSeconds(10))) {
                    continue; 
                }

                lastDbUpdateTimes.put(debounceKey, java.time.Instant.now());

                java.util.concurrent.Future<?> future = executorService.submit(() -> {
                    try {
                        processSighting(sighting);
                    } catch (Exception e) {
                        if (running) LOG.error("Error processing network sighting event in executor thread", e);
                    }
                });

                // Enforce dynamic timeout to prevent thread starvation
                timeoutScheduler.schedule(() -> {
                    if (!future.isDone()) {
                        boolean cancelled = future.cancel(true);
                        if (cancelled) {
                            LOG.warn("Dynamic timeout (" + dynamicTimeoutMs + "ms) reached. Task cancelled for IP: " + sighting.ipAddress);
                        }
                    }
                }, dynamicTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running) LOG.error("Error taking sighting from queue", e);
            }
        }
    }

    public int getActiveScanPermitsAvailable() {
        return activeScanConcurrency.availablePermits();
    }

    public int getProcessingPermitsAvailable() {
        if (executorService instanceof java.util.concurrent.ThreadPoolExecutor) {
            return ((java.util.concurrent.ThreadPoolExecutor) executorService).getQueue().remainingCapacity();
        }
        return 0;
    }

    public void updateProbeCounters(java.util.Set<String> liveIps) {
        livenessManager.updateProbeCounters(liveIps);
    }

    public void flushAndClear() {
        sightingQueue.clear();
        lastDbUpdateTimes.clear();
        while (activeProcessingCount.get() > 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        dbLock.lock();
        try {
            // Wait for any in-progress sighting processing to complete
        } finally {
            dbLock.unlock();
        }
    }

    protected void processSighting(NetworkSighting sighting) {
        if (sighting == null || sighting.ipAddress == null || "0.0.0.0".equals(sighting.ipAddress) || "255.255.255.255".equals(sighting.ipAddress) || sighting.ipAddress.startsWith("127.")) {
            LOG.debug("Ignoring sighting with invalid or non-routable IP address: " + (sighting != null ? sighting.ipAddress : "null"));
            if (sighting != null && sighting.macAddress != null && !"00:00:00:00:00:00".equals(sighting.macAddress) && !sighting.macAddress.isEmpty()) {
                FingerprintVector candidate = parseMetadata(sighting);
                identityManager.lock();
                try {
                    identityManager.mergeMetadataByMacInTransaction(sighting.macAddress, candidate);
                } finally {
                    identityManager.unlock();
                }
            }
            return;
        }

        // 1. Parse sighting metadata into candidate FingerprintVector
        FingerprintVector candidate = parseMetadata(sighting);

        // Throttle active scanning (port scans, DNS resolution) to at most once every 5 minutes per IP
        // This prevents infinite loops where our active scan triggers a packet response, which triggers another sighting, which triggers another scan...
        java.time.Instant lastScan = lastScanTimes.get(sighting.ipAddress);
        boolean isTest = io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST;
        boolean forceScan = System.getProperty("forceNetworkScan") != null;
        boolean isManual = "MANUAL_DISCOVERY".equals(sighting.source);
        boolean shouldScan = isTest || forceScan || isManual || lastScan == null || java.time.Instant.now().isAfter(lastScan.plusSeconds(300));

        String hostname = null;
        if (shouldScan) {
            try {
                activeScanConcurrency.acquire();
                try {
                    lastScanTimes.put(sighting.ipAddress, Instant.now());
                    
                    ProbeContext context = new ProbeContext(sighting.ipAddress, candidate);
                    for (NetworkProbe probe : sortedProbes) {
                        try {
                            probe.execute(context);
                        } catch (Exception e) {
                            LOG.error("Probe " + probe.getClass().getSimpleName() + " failed for IP " + sighting.ipAddress, e);
                        }
                    }
                    hostname = context.getResolvedHostname();
                    
                } finally {
                    activeScanConcurrency.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Fallback to sighting metadata host if lookup fails
        if (hostname == null && sighting.rawMetadata != null) {
            try {
                var json = MAPPER.readTree(sighting.rawMetadata);
                if (json.has("host") && !json.get("host").asText().isEmpty()) {
                    hostname = json.get("host").asText();
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        candidate.hostname = hostname;

        identityManager.lock();
        try {
            identityManager.saveSightingInTransaction(sighting, candidate, hostname);
        } finally {
            identityManager.unlock();
        }
    }















    private FingerprintVector parseMetadata(NetworkSighting sighting) {
        FingerprintVector v = new FingerprintVector();
        v.macOui = sighting.macAddress.length() >= 8 ? sighting.macAddress.substring(0, 8) : "";
        v.capturedAt = sighting.observedAt;

        if (sighting.rawMetadata != null) {
            try {
                var json = MAPPER.readTree(sighting.rawMetadata);
                if (json.has("dhcpOption55")) {
                    v.dhcpOption55 = json.get("dhcpOption55").asText();
                }
                if (json.has("dhcpOption60")) {
                    v.dhcpOption60 = json.get("dhcpOption60").asText();
                }
                if (json.has("host")) {
                    v.hostname = json.get("host").asText();
                }
                if (json.has("services")) {
                    List<String> svcs = new ArrayList<>();
                    json.get("services").forEach(node -> svcs.add(node.asText()));
                    v.mdnsServices = svcs;
                }
                if (json.has("ports")) {
                    List<Integer> pts = new ArrayList<>();
                    json.get("ports").forEach(node -> pts.add(node.asInt()));
                    v.openPorts = pts;
                }
            } catch (Exception e) {
                // Parsing issues, fallback to empty metadata
            }
        }
        return v;
    }

    public void checkSignatureMutations(FingerprintVector candidate, FingerprintVector historical, NetworkSighting sighting) {
        GlobalSetting modeSetting = GlobalSetting.findById("APP_MODE");
        String appMode = modeSetting != null ? modeSetting.value : "DISCOVERY";

        boolean isManaged = historical.physicalDevice != null && historical.physicalDevice.managementState == com.gnm.model.enums.ManagementState.MANAGED;

        if ("DETECTION".equals(appMode) || isManaged) {
            if (candidate.openPorts != null && !candidate.openPorts.isEmpty()) {
                for (Integer port : candidate.openPorts) {
                    if (historical.openPorts != null && !historical.openPorts.contains(port)) {
                        String desc = "New unexpected open port detected: " + port;
                        ThreatEvent existing = ThreatEvent.find("physicalDeviceId = ?1 and description = ?2", historical.physicalDevice.id, desc).firstResult();
                        if (existing != null) {
                            if (!existing.resolved) {
                                existing.detectedAt = Instant.now();
                                existing.persist();
                                threatBroadcaster.fire(existing);
                            }
                        } else {
                            ThreatEvent threat = new ThreatEvent();
                            threat.severity = "MEDIUM";
                            threat.description = desc;
                            threat.physicalDeviceId = historical.physicalDevice.id;
                            threat.ipAddress = sighting.ipAddress;
                            threat.macAddress = sighting.macAddress;
                            threat.detectedAt = Instant.now();
                            threat.persist();
                            threatBroadcaster.fire(threat);
                        }
                    }
                }
            }
            if (candidate.sshHostKeys != null && !candidate.sshHostKeys.isEmpty()) {
                for (String key : candidate.sshHostKeys) {
                    LOG.infof("Checking SSH Key %s against historical keys: %s", key, historical.sshHostKeys);
                    if (historical.sshHostKeys != null && !historical.sshHostKeys.isEmpty()) {
                        if (!historical.sshHostKeys.contains(key)) {
                            String desc = "SSH Host Key mutation detected! Remote host identification has changed. Key: " + key;
                            ThreatEvent existing = ThreatEvent.find("physicalDeviceId = ?1 and description = ?2", historical.physicalDevice.id, desc).firstResult();
                            if (existing != null) {
                                if (!existing.resolved) {
                                    existing.detectedAt = Instant.now();
                                    existing.persist();
                                    threatBroadcaster.fire(existing);
                                }
                            } else {
                                ThreatEvent threat = new ThreatEvent();
                                threat.severity = "HIGH";
                                threat.description = desc;
                                threat.physicalDeviceId = historical.physicalDevice.id;
                                threat.ipAddress = sighting.ipAddress;
                                threat.macAddress = sighting.macAddress;
                                threat.detectedAt = Instant.now();
                                threat.persist();
                                threatBroadcaster.fire(threat);
                            }
                        } else {
                            // Auto-mitigation: Key is trusted! Resolve any pending SSH mismatch alarms.
                            LOG.infof("SSH Key %s is trusted. Searching for unresolved threats for device %s", key, historical.physicalDevice.id);
                            List<ThreatEvent> unresolved = ThreatEvent.list("physicalDeviceId", historical.physicalDevice.id);
                            for (ThreatEvent threat : unresolved) {
                                if (!threat.resolved && threat.description != null && threat.description.startsWith("SSH Host Key mutation")) {
                                    LOG.infof("Auto-mitigating threat %s", threat.id);
                                    threat.resolved = true;
                                    threat.notes = "Key reverted to original trusted value. Previous mismatch may indicate a temporary MitM attack or network misconfiguration.";
                                    threat.persist();
                                    threatBroadcaster.fire(threat);
                                }
                            }
                        }
                    }
                }
            }
        }
    }



    public void mergeVectors(FingerprintVector source, FingerprintVector dest) {
        if (source.dhcpOption55 != null) dest.dhcpOption55 = source.dhcpOption55;
        if (source.dhcpOption60 != null) dest.dhcpOption60 = source.dhcpOption60;
        if (source.tcpFingerprint != null) dest.tcpFingerprint = source.tcpFingerprint;
        if (source.mdnsServices != null && !source.mdnsServices.isEmpty()) {
            dest.mdnsServices = source.mdnsServices;
            FingerprintVector.update("mdnsServices = ?1 where id = ?2", source.mdnsServices, dest.id);
        }
        if (source.openPorts != null && !source.openPorts.isEmpty()) {
            dest.openPorts = source.openPorts;
            FingerprintVector.update("openPorts = ?1 where id = ?2", source.openPorts, dest.id);
        }
        if (source.httpServerHeader != null) dest.httpServerHeader = source.httpServerHeader;
        if (source.tlsJa4 != null) dest.tlsJa4 = source.tlsJa4;
        if (source.tlsCertSubject != null) dest.tlsCertSubject = source.tlsCertSubject;
        if (source.ssdpUsn != null && !source.ssdpUsn.isEmpty()) dest.ssdpUsn = source.ssdpUsn;
        if (source.hostname != null && !source.hostname.isEmpty()) dest.hostname = source.hostname; // Keep hostname current
        if (source.sshHostKeys != null && !source.sshHostKeys.isEmpty()) {
            GlobalSetting modeSetting = GlobalSetting.findById("APP_MODE");
            String appMode = modeSetting != null ? modeSetting.value : "DISCOVERY";
            boolean isManaged = dest.physicalDevice != null && dest.physicalDevice.managementState == com.gnm.model.enums.ManagementState.MANAGED;
            
            // Only merge new SSH keys automatically if not managed and not in detection mode
            if (!"DETECTION".equals(appMode) && !isManaged) {
                for (String key : source.sshHostKeys) {
                    if (!dest.sshHostKeys.contains(key)) {
                        dest.sshHostKeys.add(key);
                    }
                }
            }
        }
        dest.capturedAt = Instant.now();
        dest.persist();
}
}
