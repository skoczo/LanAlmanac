package com.gnm.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.gnm.model.NetworkSighting;

@ApplicationScoped
public class IcmpSweeper {

    private static final Logger LOG = Logger.getLogger(IcmpSweeper.class);

    // Hard deadline for a full /24 sweep — hosts that don't answer within this window are considered offline
    private static final int SWEEP_TIMEOUT_SECONDS = 60;

    // Limit concurrency to prevent fork bombs when calling native binaries
    private static final java.util.concurrent.Semaphore processPermits = new java.util.concurrent.Semaphore(50);

    // Per-host timeouts — balance detection reliability vs sweep speed
    // 1500ms ping: covers high-latency IoT/WiFi devices (e.g. 650ms RTT on congested WiFi)
    // Safe because virtual threads + 15s hard deadline guarantee the sweep always finishes
    private static final int PING_TIMEOUT_MS  = 1500;
    private static final int PORT_TIMEOUT_MS  =   80;
    private static final int ARP_TIMEOUT_MS   =  800;  // arping covers Android Doze Mode devices
    private static final int[] PROBE_PORTS    = { 22, 80, 443, 445 };

    @Inject
    NetworkSightingQueue sightingQueue;

    @ConfigProperty(name = "gnm.subnet", defaultValue = "192.168.1.0/24")
    String subnetConfig;

    public java.util.Set<String> sweep() {
        String[] subnets = subnetConfig.split(",");
        java.util.Set<String> allLiveIps = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        // Fan out all subnets in parallel so a slow subnet doesn't block the others.
        try (ExecutorService subnetExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<java.util.Set<String>>> subnetFutures = new ArrayList<>();
            for (String subnet : subnets) {
                String trimmed = subnet.trim();
                subnetFutures.add(CompletableFuture.supplyAsync(() -> sweepSubnet(trimmed), subnetExecutor));
            }
            for (CompletableFuture<java.util.Set<String>> f : subnetFutures) {
                try {
                    allLiveIps.addAll(f.get(SWEEP_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS));
                } catch (Exception ignored) {}
            }
        }
        return allLiveIps;
    }

    private java.util.Set<String> sweepSubnet(String subnet) {
        LOG.info("Starting active ICMP sweep on subnet: " + subnet);

        String[] parts = subnet.split("/");
        String baseIp = parts[0];
        int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : 24;

        if (prefix != 24) {
            LOG.warn("ICMP Sweeper currently only supports /24 subnets. Skipping scan.");
            return java.util.Collections.emptySet();
        }

        String base = baseIp.substring(0, baseIp.lastIndexOf('.') + 1);

        long startTime = System.currentTimeMillis();
        java.util.Set<String> liveIps = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        // Use virtual threads — one per IP. The OS scheduler parks them cheaply while waiting for I/O.
        // A hard timeout of SWEEP_TIMEOUT_SECONDS ensures the sweep always completes promptly.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>(254);
            for (int i = 1; i <= 254; i++) {
                final String ip = base + i;
                futures.add(CompletableFuture.runAsync(() -> probeIp(ip, liveIps), executor));
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(SWEEP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                LOG.warnf("ICMP sweep for %s hit %ds hard deadline — %d hosts responded so far.",
                    subnet, SWEEP_TIMEOUT_SECONDS, liveIps.size());
                // Cancel remaining probes
                futures.forEach(f -> f.cancel(true));
            } catch (Exception ignored) {}
        }

        LOG.info("Active ICMP sweep completed in " + (System.currentTimeMillis() - startTime) + " ms.");
        return liveIps;
    }

    private void probeIp(String ip, java.util.Set<String> liveIps) {
        try {
            if (isReachable(ip)) {
                LOG.debug("Host responsive to hybrid probe: " + ip);
                liveIps.add(ip);

                NetworkSighting sighting = new NetworkSighting();
                sighting.ipAddress = ip;
                sighting.macAddress = "00:00:00:00:00:00"; // MAC will be filled by ARP scan
                sighting.source = "ICMP_SWEEP";
                sighting.observedAt = Instant.now();
                sighting.rawMetadata = "{}";

                sightingQueue.offer(sighting);
            }
        } catch (Exception e) {
            // Skip unreachable hosts silently
        }
    }

    private boolean isReachable(String ip) {
        // 1. Try system ping (-W 1 is the minimum on Linux; waitFor enforces our tighter timeout)
        Process p = null;
        try {
            processPermits.acquire();
            try {
                p = new ProcessBuilder("ping", "-n", "-c", "1", "-W", "1", ip)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                boolean completed = p.waitFor(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (completed && p.exitValue() == 0) {
                    return true;
                } else if (!completed) {
                    p.destroyForcibly();
                }
            } finally {
                processPermits.release();
            }
        } catch (Exception e) {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }

        // 2. Try TCP port probe — "Connection refused" still means the host is alive.
        for (int port : PROBE_PORTS) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(ip, port), PORT_TIMEOUT_MS);
                return true;
            } catch (java.io.IOException e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("refused")) {
                    return true;
                }
            }
        }

        // 3. ARP who-has probe — works for devices in Android Doze Mode / iOS sleep that
        //    block ICMP and incoming TCP but still respond to ARP at the L2 level.
        //    arping -C 1 sends exactly one ARP request and exits 0 if answered.
        Process arp = null;
        try {
            processPermits.acquire();
            try {
                arp = new ProcessBuilder("arping", "-c", "1", "-w", "1", "-I", getNetworkInterface(), ip)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                boolean completed = arp.waitFor(ARP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (completed && arp.exitValue() == 0) {
                    return true;
                } else if (!completed) {
                    arp.destroyForcibly();
                }
            } finally {
                processPermits.release();
            }
        } catch (Exception e) {
            if (arp != null && arp.isAlive()) arp.destroyForcibly();
            // arping may not be installed; fall through silently
        }

        return false;
    }

    private String getNetworkInterface() {
        // Read the same setting the rest of the app uses
        try {
            com.gnm.model.GlobalSetting setting = com.gnm.model.GlobalSetting.findById("gnm.listen.interface");
            if (setting != null && setting.value != null && !setting.value.isBlank()) {
                return setting.value.trim();
            }
        } catch (Exception ignored) {}
        return "eth0";
    }
}
