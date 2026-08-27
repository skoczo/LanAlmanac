package com.gnm.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;
import com.gnm.model.GlobalSetting;
import com.gnm.fingerprint.FingerprintEngine;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class DiscoveryScheduler {

    private static final Logger LOG = Logger.getLogger(DiscoveryScheduler.class);

    @Inject
    PassivePacketListener passivePacketListener;

    @Inject
    IcmpSweeper icmpSweeper;

    @Inject
    FingerprintEngine fingerprintEngine;

    @Inject
    ArpScanner arpScanner;

    public void onStart(@Observes StartupEvent ev) {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST) {
            LOG.info("Test mode detected, disabling active and passive network discovery.");
            return;
        }
        LOG.info("Application starting. Initializing passive packet capturing thread...");
        Thread.startVirtualThread(() -> {
            passivePacketListener.startCapture();
        });
    }

    public void onStop(@Observes ShutdownEvent ev) {
        LOG.info("Application stopping. Shutting down passive packet capturing...");
        passivePacketListener.stop();
    }

    @Scheduled(every = "${gnm.scan.icmp-interval:60s}", identity = "icmp-sweep-job")
    public void triggerIcmpSweep() {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST) return;
        GlobalSetting setting = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> GlobalSetting.findById("ENABLE_ACTIVE_SCANNING"));
        if (setting != null && "false".equalsIgnoreCase(setting.value)) {
            LOG.debug("Active scanning is disabled via settings. Skipping ICMP sweep.");
            return;
        }
        LOG.debug("Scheduled trigger: running active ICMP sweep...");
        java.util.Set<String> liveIps = icmpSweeper.sweep();

        // Also merge active system ARP cache IPs so Doze mode / non-ICMP hosts are counted as live
        try {
            java.util.Set<String> arpIps = arpScanner.scan();
            if (arpIps != null) {
                liveIps.addAll(arpIps);
            }
        } catch (Exception e) {
            LOG.warn("Failed to collect ARP live IPs for probe update", e);
        }

        // After sweep, update probe counters so FingerprintEngine can decide which
        // devices missed this cycle and potentially transition them to OFFLINE.
        fingerprintEngine.updateProbeCounters(liveIps);
    }

    @Scheduled(every = "${gnm.scan.arp-interval:30s}", identity = "arp-scan-job")
    public void triggerArpScan() {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST) return;
        GlobalSetting setting = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> GlobalSetting.findById("ENABLE_ACTIVE_SCANNING"));
        if (setting != null && "false".equalsIgnoreCase(setting.value)) {
            LOG.debug("Active scanning is disabled via settings. Skipping ARP scan.");
            return;
        }
        LOG.debug("Scheduled trigger: running active ARP scan...");
        arpScanner.scan();
    }
}
