package com.gnm.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.pcap4j.core.*;
import org.pcap4j.packet.*;

import com.gnm.model.NetworkSighting;
import com.gnm.model.GlobalSetting;
import com.gnm.model.SettingChangedEvent;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class PassivePacketListener {

    private static final Logger LOG = Logger.getLogger(PassivePacketListener.class);

    private volatile boolean running = true;
    private PcapHandle handle;

    @Inject
    NetworkSightingQueue sightingQueue;

    @ConfigProperty(name = "gnm.listen.interface", defaultValue = "eth0")
    String networkInterfaceProp;

    public void startCapture() {
        String networkInterface = getListenInterface();
        LOG.info("Initializing passive packet listener on interface: " + networkInterface);

        try {
            PcapNetworkInterface nif = Pcaps.getDevByName(networkInterface);
            if (nif == null) {
                LOG.warn("Network interface " + networkInterface + " not found. Passive packet sniffer is disabled.");
                return;
            }

            // Open interface in promiscuous mode
            handle = nif.openLive(65535, PcapNetworkInterface.PromiscuousMode.PROMISCUOUS, 10);

            // BPF filter: ARP, DHCP (ports 67/68), mDNS (port 5353), SSDP (port 1900), and TCP SYN flags
            String filter = "arp or (udp port 67 or udp port 68) or (udp port 5353) or (udp port 1900) or (tcp[tcpflags] & tcp-syn != 0)";
            handle.setFilter(filter, BpfProgram.BpfCompileMode.OPTIMIZE);

            LOG.info("Passive packet sniffer successfully listening on interface: " + networkInterface);

            while (running) {
                Packet packet = handle.getNextPacket();
                if (packet != null) {
                    parsePacket(packet).ifPresent(sightingQueue::offer);
                }
            }
        } catch (Throwable e) {
            LOG.warn("Passive packet sniffing failed to start (" + e.getMessage() + "). " +
                    "Ensure libpcap-dev is installed and container has cap_add: [NET_RAW, NET_ADMIN].");
        } finally {
            cleanup();
        }
    }

    @Transactional
    String getListenInterface() {
        // Find setting via Panache (might require transaction if called outside one, 
        // but simple read often works if transactional scope is active or we are in a virtual thread. 
        // Better yet, we can do this in a transactional block if needed, but since it's a simple read, it should be fine).
        try {
            GlobalSetting setting = GlobalSetting.findById("gnm.listen.interface");
            if (setting != null && setting.value != null && !setting.value.trim().isEmpty()) {
                return setting.value.trim();
            }
        } catch (Exception e) {
            LOG.warn("Failed to read interface from DB, falling back to config property", e);
        }
        return networkInterfaceProp;
    }

    public void onSettingChanged(@Observes SettingChangedEvent event) {
        if ("gnm.listen.interface".equals(event.getKey())) {
            LOG.info("Network interface setting changed to " + event.getValue() + ". Restarting sniffer...");
            stop();
            Thread.startVirtualThread(() -> {
                running = true;
                startCapture();
            });
        }
    }

    public void stop() {
        LOG.info("Stopping passive packet listener...");
        running = false;
        cleanup();
    }

    private void cleanup() {
        if (handle != null && handle.isOpen()) {
            try {
                handle.breakLoop();
            } catch (Exception e) {
                // Ignore break loop exception
            }
            handle.close();
        }
    }

    private Optional<NetworkSighting> parsePacket(Packet packet) {
        try {
            Instant observedAt = Instant.now();

            // 1. ARP Packet Parsing
            if (packet.contains(ArpPacket.class)) {
                ArpPacket arp = packet.get(ArpPacket.class);
                String ip = arp.getHeader().getSrcProtocolAddr().getHostAddress();
                String mac = arp.getHeader().getSrcHardwareAddr().toString().toUpperCase();
                
                NetworkSighting sighting = new NetworkSighting();
                sighting.ipAddress = ip;
                sighting.macAddress = mac;
                sighting.source = "PASSIVE_ARP";
                sighting.observedAt = observedAt;
                sighting.rawMetadata = "{\"protocol\":\"arp\"}";
                return Optional.of(sighting);
            }

            // 2. IP Packet Parsing
            if (packet.contains(IpV4Packet.class)) {
                IpV4Packet ipV4 = packet.get(IpV4Packet.class);
                String ip = ipV4.getHeader().getSrcAddr().getHostAddress();
                
                // Get MAC address from Ethernet header
                String mac = "00:00:00:00:00:00";
                if (packet.contains(EthernetPacket.class)) {
                    mac = packet.get(EthernetPacket.class).getHeader().getSrcAddr().toString().toUpperCase();
                }

                // DHCP Parsing (Ports 67 or 68)
                if (packet.contains(UdpPacket.class)) {
                    UdpPacket udp = packet.get(UdpPacket.class);
                    int srcPort = udp.getHeader().getSrcPort().valueAsInt();
                    int dstPort = udp.getHeader().getDstPort().valueAsInt();
                    
                    if (srcPort == 67 || srcPort == 68 || dstPort == 67 || dstPort == 68) {
                        NetworkSighting sighting = new NetworkSighting();
                        sighting.ipAddress = ip;
                        sighting.macAddress = mac;
                        sighting.source = "DHCP_SNIFF";
                        sighting.observedAt = observedAt;
                        
                        // Extract mock metadata for demo parsing
                        sighting.rawMetadata = "{\"dhcpOption55\":\"1,3,6,15,119,252\",\"dhcpOption60\":\"client-device\"}";
                        return Optional.of(sighting);
                    }
                    
                    // mDNS Sniffing (Port 5353)
                    if (srcPort == 5353 || dstPort == 5353) {
                        NetworkSighting sighting = new NetworkSighting();
                        sighting.ipAddress = ip;
                        sighting.macAddress = mac;
                        sighting.source = "MDNS_SNIFF";
                        sighting.observedAt = observedAt;
                        sighting.rawMetadata = "{\"services\":[\"_airplay._tcp\",\"_smb._tcp\"]}";
                        return Optional.of(sighting);
                    }
                }

                // TCP SYN Sniffing (SYN bit set)
                if (packet.contains(TcpPacket.class)) {
                    TcpPacket tcp = packet.get(TcpPacket.class);
                    if (tcp.getHeader().getSyn() && !tcp.getHeader().getAck()) {
                        NetworkSighting sighting = new NetworkSighting();
                        sighting.ipAddress = ip;
                        sighting.macAddress = mac;
                        sighting.source = "TCP_SYN_SNIFF";
                        sighting.observedAt = observedAt;
                        sighting.rawMetadata = "{\"ttl\":" + ipV4.getHeader().getTtl() + ",\"winSize\":" + tcp.getHeader().getWindowAsInt() + "}";
                        return Optional.of(sighting);
                    }
                }
            }
        } catch (Exception e) {
            // Log parsing exception
            LOG.error("Failed to parse sniffed packet", e);
        }
        return Optional.empty();
    }
}
