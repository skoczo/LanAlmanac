package com.gnm.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.gnm.model.NetworkSighting;

@ApplicationScoped
public class IcmpSweeper {

    private static final Logger LOG = Logger.getLogger(IcmpSweeper.class);

    @Inject
    NetworkSightingQueue sightingQueue;

    @ConfigProperty(name = "gnm.subnet", defaultValue = "192.168.1.0/24")
    String subnetConfig;

    public java.util.Set<String> sweep() {
        String[] subnets = subnetConfig.split(",");
        java.util.Set<String> allLiveIps = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        // Fan out all subnets in parallel virtual threads so a slow sweep of one subnet
        // does not block the others from being refreshed within the same scheduler tick.
        try (java.util.concurrent.ExecutorService subnetExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.List<java.util.concurrent.Future<java.util.Set<String>>> subnetFutures = new java.util.ArrayList<>();
            for (String subnet : subnets) {
                String trimmed = subnet.trim();
                subnetFutures.add(subnetExecutor.submit(() -> sweepSubnet(trimmed)));
            }
            for (java.util.concurrent.Future<java.util.Set<String>> f : subnetFutures) {
                try { allLiveIps.addAll(f.get()); } catch (Exception ignored) {}
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
        List<String> targetIps = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            targetIps.add(base + i);
        }

        long startTime = System.currentTimeMillis();
        java.util.Set<String> liveIps = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        // Spawn parallel threads to probe each IP (limited to 50 concurrent)
        try (ExecutorService executor = Executors.newFixedThreadPool(50)) {
            List<Future<Void>> futures = targetIps.stream()
                .map(ip -> executor.submit(() -> {
                    probeIp(ip, liveIps);
                    return (Void) null;
                }))
                .toList();

            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    // Ignore individual task exceptions to let other pings finish
                }
            }
        }

        LOG.info("Active ICMP sweep completed in " + (System.currentTimeMillis() - startTime) + " ms.");
        return liveIps;
    }

    private void probeIp(String ip, java.util.Set<String> liveIps) {
        try {
            if (isReachable(ip)) {
                LOG.debug("Host responsive to hybrid probe: " + ip);
                liveIps.add(ip);
                
                // Create a raw network sighting
                NetworkSighting sighting = new NetworkSighting();
                sighting.ipAddress = ip;
                sighting.macAddress = "00:00:00:00:00:00"; // MAC will be filled by ARP scan
                sighting.source = "ICMP_SWEEP";
                sighting.observedAt = Instant.now();
                sighting.rawMetadata = "{}";
                
                sightingQueue.offer(sighting);
            }
        } catch (Exception e) {
            // Skip
        }
    }

    private boolean isReachable(String ip) {
        // 1. Try system ping command
        Process p = null;
        try {
            p = new ProcessBuilder("ping", "-c", "1", "-W", "1", ip)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            boolean completed = p.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (completed) {
                if (p.exitValue() == 0) {
                    return true;
                }
            } else {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }

        // 2. Try port sweep (22, 80, 443, 137, 445).
        // Active "Connection Refused" means the host is alive and responded!
        int[] ports = { 22, 80, 443, 137, 445 };
        for (int port : ports) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(ip, port), 100);
                return true;
            } catch (java.io.IOException e) {
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("refused")) {
                    return true;
                }
            }
        }
        return false;
    }
}
