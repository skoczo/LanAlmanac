package com.gnm.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;
import com.gnm.model.GlobalSetting;
import com.gnm.fingerprint.FingerprintEngine;

@ApplicationScoped
public class DiscoveryScheduler {

    private static final Logger LOG = Logger.getLogger(DiscoveryScheduler.class);

    @Inject
    private PassivePacketListener passivePacketListener;

    @Inject
    private IcmpSweeper icmpSweeper;

    @Inject
    private FingerprintEngine fingerprintEngine;

    @Inject
    private ArpScanner arpScanner;



    @Inject
    private DiscoveryModuleManager moduleManager;

    public void onStart(@Observes StartupEvent ev) {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST) {
            LOG.info("Test mode detected, disabling active and passive network discovery.");
            return;
        }
        LOG.info("Application starting. Initializing passive packet capturing thread...");
        Thread t = new Thread(() -> {
            passivePacketListener.startCapture();
        });
        t.setName("PassivePacketListener");
        t.setDaemon(true);
        t.start();

        // Initial startup scan: populate devices once on application startup
        Thread.ofVirtual().start(() -> {
            io.quarkus.arc.Arc.container().requestContext().activate();
            try {
                LOG.info("Running initial startup ARP and ICMP scan...");
                java.util.Set<String> liveIps = arpScanner.scan();
                java.util.Set<String> icmpIps = icmpSweeper.sweep();
                if (icmpIps != null) {
                    liveIps.addAll(icmpIps);
                }
                LOG.info("Initial startup scan completed successfully.");
            } catch (Exception e) {
                LOG.warn("Initial startup scan encountered an error: " + e.getMessage(), e);
            } finally {
                io.quarkus.arc.Arc.container().requestContext().terminate();
            }
        });
    }

    public void onStop(@Observes ShutdownEvent ev) {
        LOG.info("Application stopping. Shutting down passive packet capturing...");
        passivePacketListener.stop();
    }

    @Scheduled(every = "${gnm.scan.icmp-interval:12h}", identity = "icmp-sweep-job")
    public void triggerIcmpSweep() {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST) {
            return;
        }

        GlobalSetting setting = QuarkusTransaction.requiringNew()
                .call(() -> GlobalSetting.findById("ENABLE_ACTIVE_SCANNING"));
        if (setting != null && "false".equalsIgnoreCase(setting.value)) {
            LOG.debug("Active scanning is disabled via settings. Skipping ICMP sweep.");
            return;
        }

        com.gnm.discovery.model.DiscoveryModuleStatus mod = moduleManager.getModuleStatus(DiscoveryModuleManager.ICMP_SWEEPER_ID);
        if (mod == null || !mod.enabled) {
            LOG.debug("ICMP Sweeper module is disabled. Skipping scheduled ICMP sweep.");
            return;
        }

        LOG.debug("Scheduled trigger: running active ICMP sweep...");
        java.util.Set<String> skipIps = QuarkusTransaction.requiringNew().call(() -> com.gnm.model.NetworkIdentity.getOnlineIps());
        icmpSweeper.sweep(skipIps);
    }

    @Scheduled(every = "${gnm.scan.arp-interval:24h}", identity = "arp-scan-job")
    public void triggerArpScan() {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST) {
            return;
        }

        GlobalSetting setting = QuarkusTransaction.requiringNew()
                .call(() -> GlobalSetting.findById("ENABLE_ACTIVE_SCANNING"));
        if (setting != null && "false".equalsIgnoreCase(setting.value)) {
            LOG.debug("Active scanning is disabled via settings. Skipping ARP scan.");
            return;
        }

        com.gnm.discovery.model.DiscoveryModuleStatus mod = moduleManager.getModuleStatus(DiscoveryModuleManager.ACTIVE_ARP_ID);
        if (mod == null || !mod.enabled) {
            LOG.debug("ARP Scanner module is disabled. Skipping scheduled ARP scan.");
            return;
        }

        LOG.debug("Scheduled trigger: running active ARP scan...");
        java.util.Set<String> skipIps = QuarkusTransaction.requiringNew().call(() -> com.gnm.model.NetworkIdentity.getOnlineIps());
        arpScanner.scan(skipIps);
    }

}
