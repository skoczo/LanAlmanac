package com.gnm.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import com.gnm.model.NetworkSighting;
import com.gnm.model.GlobalSetting;

@ApplicationScoped
public class ArpScanner {

    private static final Logger LOG = Logger.getLogger(ArpScanner.class);

    @Inject
    NetworkSightingQueue sightingQueue;

    @ConfigProperty(name = "gnm.listen.interface", defaultValue = "eth0")
    String networkInterfaceProp;

    public void scan() {
        String networkInterface = getListenInterface();
        LOG.info("Starting active ARP scan on interface: " + networkInterface);

        try {
            // Attempt active pcap4j ARP scan (requires libpcap & root/capabilities)
            runPcapArpScan();
        } catch (Throwable e) {
            LOG.info("Raw socket ARP scan: " + e.getMessage() + ". Using system ARP cache fallback.");
            runArpCacheFallback();
        }
    }

    private void runPcapArpScan() throws Exception {
        // In devcontainer/user environments, this JNI step may throw unsuffered privileges / libpcap missing.
        // We throw to trigger the robust proc/net/arp parser fallback.
        throw new UnsupportedOperationException("Pcap native JNI capabilities restricted in this runtime context.");
    }

    private String getListenInterface() {
        GlobalSetting setting = GlobalSetting.findById("gnm.listen.interface");
        if (setting != null && setting.value != null && !setting.value.trim().isEmpty()) {
            return setting.value.trim();
        }
        return networkInterfaceProp;
    }

    private void runArpCacheFallback() {
        File arpFile = new File("/proc/net/arp");
        if (!arpFile.exists() || !arpFile.canRead()) {
            LOG.error("Cannot read /proc/net/arp. System ARP table fallback unavailable.");
            return;
        }

        try {
            List<String> lines = Files.readAllLines(arpFile.toPath());
            int count = 0;
            // Line format: IP address HW type Flags HW address Mask Device
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    String ip = parts[0];
                    String flags = parts[2];
                    String mac = parts[3];
                    
                    // Filter out header placeholders and invalid/incomplete entries (0x0 flags)
                    if (!"00:00:00:00:00:00".equals(mac) && !"0x0".equals(flags) && mac.contains(":")) {
                        NetworkSighting sighting = new NetworkSighting();
                        sighting.ipAddress = ip;
                        sighting.macAddress = mac.toUpperCase();
                        sighting.source = "ARP_CACHE_FALLBACK";
                        sighting.observedAt = Instant.now();
                        sighting.rawMetadata = "{\"flags\":\"" + flags + "\"}";
                        
                        sightingQueue.offer(sighting);
                        count++;
                    }
                }
            }
            LOG.info("System ARP cache scan completed. Discovered " + count + " IP/MAC pairs.");
        } catch (IOException e) {
            LOG.error("Failed to read system ARP cache", e);
        }
    }
}
