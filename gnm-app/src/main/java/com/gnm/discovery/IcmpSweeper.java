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

    public void sweep() {
        String[] subnets = subnetConfig.split(",");
        for (String subnet : subnets) {
            sweepSubnet(subnet.trim());
        }
    }

    private void sweepSubnet(String subnet) {
        LOG.info("Starting active ICMP sweep on subnet: " + subnet);
        
        String[] parts = subnet.split("/");
        String baseIp = parts[0];
        int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : 24;

        if (prefix != 24) {
            LOG.warn("ICMP Sweeper currently only supports /24 subnets. Skipping scan.");
            return;
        }

        String base = baseIp.substring(0, baseIp.lastIndexOf('.') + 1);
        List<String> targetIps = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            targetIps.add(base + i);
        }

        long startTime = System.currentTimeMillis();

        // Spawn parallel virtual threads to probe each IP
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = targetIps.stream()
                .map(ip -> executor.submit(() -> {
                    probeIp(ip);
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
    }

    private void probeIp(String ip) {
        try {
            if (isReachable(ip)) {
                LOG.debug("Host responsive to hybrid probe: " + ip);
                
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
        // 1. Try system ping command (works for non-root on Linux due to SUID)
        try {
            Process p = new ProcessBuilder("ping", "-c", "1", "-W", "1", ip).start();
            if (p.waitFor() == 0) {
                return true;
            }
        } catch (Exception e) {
            // Fallback to TCP checks
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
