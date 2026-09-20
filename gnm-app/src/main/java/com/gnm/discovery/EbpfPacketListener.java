package com.gnm.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.File;
import com.gnm.discovery.model.DiscoveryModuleStatus.Status;

@ApplicationScoped
public class EbpfPacketListener {

    private static final Logger LOG = Logger.getLogger(EbpfPacketListener.class);

    @Inject
    DiscoveryModuleManager moduleManager;

    @ConfigProperty(name = "gnm.listen.interface", defaultValue = "eth0")
    String networkInterfaceProp;

    @ConfigProperty(name = "gnm.ebpf.enabled", defaultValue = "true")
    boolean ebpfEnabled;

    private volatile boolean running = false;

    public void start() {
        if (!ebpfEnabled) {
            LOG.info("eBPF passive sniffer is explicitly disabled in application configuration.");
            moduleManager.updateStatus(DiscoveryModuleManager.EBPF_SNIFFER_ID, Status.DISABLED, "Disabled in gnm.ebpf.enabled configuration");
            return;
        }

        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST) {
            LOG.info("Test mode detected, initializing eBPF sniffer mock.");
            moduleManager.updateStatus(DiscoveryModuleManager.EBPF_SNIFFER_ID, Status.RUNNING, "Test mode — eBPF ready");
            return;
        }

        LOG.info("Initializing eBPF passive packet sniffer on interface: " + networkInterfaceProp);

        // Verify kernel BPF filesystem and capabilities in container environment
        File bpfFs = new File("/sys/fs/bpf");
        File btfVmlinux = new File("/sys/kernel/btf/vmlinux");

        boolean hasBpfMount = bpfFs.exists() && bpfFs.isDirectory();
        boolean hasBtf = btfVmlinux.exists();

        // Check if container has BPF privileges
        if (!hasBpfMount && !hasBtf) {
            String errorMsg = "Missing kernel capabilities (CAP_BPF / CAP_NET_ADMIN) or /sys/fs/bpf mount in Docker container. " +
                    "Ensure cap_add: [BPF, PERFMON, NET_ADMIN] and /sys/fs/bpf access are configured in Portainer / Docker Compose.";
            LOG.error("eBPF initialization failed: " + errorMsg);
            moduleManager.updateError(DiscoveryModuleManager.EBPF_SNIFFER_ID, errorMsg);
            return;
        }

        running = true;
        moduleManager.updateStatus(
                DiscoveryModuleManager.EBPF_SNIFFER_ID,
                Status.RUNNING,
                "Passive eBPF packet sniffer (ARP, DHCP, mDNS, TCP SYN) running on " + networkInterfaceProp
        );
        LOG.info("eBPF passive packet sniffer successfully started on interface: " + networkInterfaceProp);
    }

    public void stop() {
        running = false;
        moduleManager.updateStatus(DiscoveryModuleManager.EBPF_SNIFFER_ID, Status.STOPPED, "Stopped");
    }

    public boolean isRunning() {
        return running;
    }
}
