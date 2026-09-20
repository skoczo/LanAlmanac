package com.gnm.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.gnm.discovery.model.DiscoveryModuleStatus;
import com.gnm.discovery.model.DiscoveryModuleStatus.Status;

@ApplicationScoped
public class DiscoveryModuleManager {

    private static final Logger LOG = Logger.getLogger(DiscoveryModuleManager.class);

    public static final String EBPF_SNIFFER_ID = "ebpf-passive-sniffer";
    public static final String ACTIVE_ARP_ID = "active-arp-scanner";
    public static final String ICMP_SWEEPER_ID = "icmp-sweeper";

    @Inject
    ArpScanner arpScanner;

    @Inject
    IcmpSweeper icmpSweeper;

    private final Map<String, DiscoveryModuleStatus> moduleStatusMap = new ConcurrentHashMap<>();

    public DiscoveryModuleManager() {
        initDefaultModules();
    }

    private void initDefaultModules() {
        moduleStatusMap.put(EBPF_SNIFFER_ID, new DiscoveryModuleStatus(
                EBPF_SNIFFER_ID,
                "eBPF Passive Sniffer",
                Status.STOPPED,
                "Initializing eBPF sniffer module...",
                true
        ));

        moduleStatusMap.put(ACTIVE_ARP_ID, new DiscoveryModuleStatus(
                ACTIVE_ARP_ID,
                "Active ARP Scanner",
                Status.STOPPED,
                "Idle — waiting for scan",
                true
        ));

        moduleStatusMap.put(ICMP_SWEEPER_ID, new DiscoveryModuleStatus(
                ICMP_SWEEPER_ID,
                "ICMP Sweeper",
                Status.STOPPED,
                "Idle — waiting for scan",
                true
        ));
    }

    public List<DiscoveryModuleStatus> getAllModuleStatuses() {
        return new ArrayList<>(moduleStatusMap.values());
    }

    public DiscoveryModuleStatus getModuleStatus(String id) {
        return moduleStatusMap.get(id);
    }

    public void updateStatus(String id, Status status, String activity) {
        DiscoveryModuleStatus mod = moduleStatusMap.get(id);
        if (mod != null) {
            mod.status = status;
            if (activity != null) {
                mod.currentActivity = activity;
            }
            if (status == Status.RUNNING) {
                mod.errorMessage = null;
            }
        }
    }

    public void updateError(String id, String errorMessage) {
        DiscoveryModuleStatus mod = moduleStatusMap.get(id);
        if (mod != null) {
            mod.status = Status.ERROR;
            mod.errorMessage = errorMessage;
            mod.currentActivity = "Module execution error";
            LOG.errorf("Discovery module %s error: %s", id, errorMessage);
        }
    }

    public void updateLastDiscovered(String id, String summary) {
        DiscoveryModuleStatus mod = moduleStatusMap.get(id);
        if (mod != null) {
            mod.lastDiscoveredSummary = summary;
            mod.lastScanAt = Instant.now();
        }
    }

    public boolean toggleModule(String id, boolean enabled) {
        DiscoveryModuleStatus mod = moduleStatusMap.get(id);
        if (mod != null) {
            mod.enabled = enabled;
            if (!enabled) {
                mod.status = Status.DISABLED;
                mod.currentActivity = "Module disabled by user";
            } else {
                mod.status = Status.STOPPED;
                mod.currentActivity = "Module enabled — ready";
            }
            return true;
        }
        return false;
    }

    public boolean triggerScan(String id) {
        DiscoveryModuleStatus mod = moduleStatusMap.get(id);
        if (mod == null || !mod.enabled) {
            return false;
        }

        Thread.ofVirtual().start(() -> {
            try {
                if (ACTIVE_ARP_ID.equals(id)) {
                    updateStatus(id, Status.RUNNING, "Running active ARP scan on demand...");
                    arpScanner.scan();
                } else if (ICMP_SWEEPER_ID.equals(id)) {
                    updateStatus(id, Status.RUNNING, "Running ICMP sweep on demand...");
                    icmpSweeper.sweep();
                } else if (EBPF_SNIFFER_ID.equals(id)) {
                    LOG.info("Triggered refresh for eBPF sniffer status.");
                }
            } catch (Exception e) {
                updateError(id, "Error during manual scan: " + e.getMessage());
            }
        });
        return true;
    }
}
