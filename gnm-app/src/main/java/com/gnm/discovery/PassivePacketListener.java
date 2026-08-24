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

    private String localMacAddress;
    private final java.util.Set<String> localIpAddresses = new java.util.HashSet<>();

    private final java.util.Map<String, String> dhcpCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> ipToMacCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Inject
    NetworkSightingQueue sightingQueue;

    @ConfigProperty(name = "gnm.listen.interface", defaultValue = "eth0")
    String networkInterfaceProp;

    public void startCapture() {
        String networkInterface = getListenInterface();
        LOG.info("Initializing passive packet listener on interface: " + networkInterface);

        localIpAddresses.clear();
        localMacAddress = null;
        try {
            java.net.NetworkInterface netIf = java.net.NetworkInterface.getByName(networkInterface);
            if (netIf != null) {
                byte[] mac = netIf.getHardwareAddress();
                if (mac != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        sb.append(String.format("%02X", mac[i]));
                        if (i < mac.length - 1) {
                            sb.append(":");
                        }
                    }
                    localMacAddress = sb.toString();
                    LOG.info("Detected local MAC address for sniffer: " + localMacAddress);
                }
                java.util.Enumeration<java.net.InetAddress> addrs = netIf.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    String ip = addrs.nextElement().getHostAddress();
                    int percent = ip.indexOf('%');
                    if (percent > 0) {
                        ip = ip.substring(0, percent);
                    }
                    localIpAddresses.add(ip);
                }
                LOG.info("Detected local IP addresses for sniffer: " + localIpAddresses);
            }
        } catch (Exception e) {
            LOG.warn("Failed to detect local IP/MAC for interface " + networkInterface, e);
        }

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

    private Optional<NetworkSighting> emitSighting(NetworkSighting sighting) {
        if (sighting != null && sighting.macAddress != null) {
            String cachedDhcp = dhcpCache.get(sighting.macAddress);
            if (cachedDhcp != null && !cachedDhcp.equals("{}")) {
                if (sighting.rawMetadata == null || sighting.rawMetadata.isEmpty() || sighting.rawMetadata.equals("{}")) {
                    sighting.rawMetadata = cachedDhcp;
                } else if (sighting.rawMetadata.startsWith("{") && !sighting.rawMetadata.contains("dhcpOption55")) {
                    // Merge JSON by removing trailing } and appending
                    sighting.rawMetadata = sighting.rawMetadata.substring(0, sighting.rawMetadata.length() - 1) + "," + cachedDhcp.substring(1);
                }
            }
        }
        return Optional.of(sighting);
    }

    private boolean isLocal(String ip, String mac) {
        if (ip != null && localIpAddresses.contains(ip)) {
            return true;
        }
        if (mac != null && localMacAddress != null && localMacAddress.equalsIgnoreCase(mac)) {
            return true;
        }
        return false;
    }

    private Optional<NetworkSighting> parsePacket(Packet packet) {
        try {
            Instant observedAt = Instant.now();

            // 1. ARP Packet Parsing
            if (packet.contains(ArpPacket.class)) {
                ArpPacket arp = packet.get(ArpPacket.class);
                String ip = arp.getHeader().getSrcProtocolAddr().getHostAddress();
                String mac = arp.getHeader().getSrcHardwareAddr().toString().toUpperCase();
                
                if (mac != null && !"00:00:00:00:00:00".equals(mac)) {
                    ipToMacCache.put(ip, mac);
                }

                if (isLocal(ip, mac)) {
                    return Optional.empty();
                }

                NetworkSighting sighting = new NetworkSighting();
                sighting.ipAddress = ip;
                sighting.macAddress = mac;
                sighting.source = "PASSIVE_ARP";
                sighting.observedAt = observedAt;
                sighting.rawMetadata = "{\"protocol\":\"arp\"}";
                return emitSighting(sighting);
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

                if ("00:00:00:00:00:00".equals(mac)) {
                    String cachedMac = ipToMacCache.get(ip);
                    if (cachedMac != null) {
                        mac = cachedMac;
                    }
                } else {
                    ipToMacCache.put(ip, mac);
                }

                if (isLocal(ip, mac)) {
                    return Optional.empty();
                }

                // DHCP Parsing (Ports 67 or 68)
                if (packet.contains(UdpPacket.class)) {
                    UdpPacket udp = packet.get(UdpPacket.class);
                    int srcPort = udp.getHeader().getSrcPort().valueAsInt();
                    int dstPort = udp.getHeader().getDstPort().valueAsInt();
                    
                    if (srcPort == 67 || srcPort == 68 || dstPort == 67 || dstPort == 68) {
                        // Parse DHCP options and client identity from the UDP payload
                        // DHCP fixed header layout:
                        //   Offset 0:   op (1=request, 2=reply)
                        //   Offset 16:  yiaddr (your IP address - assigned by server)
                        //   Offset 28:  chaddr (client hardware/MAC address, 16 bytes)
                        //   Offset 236: magic cookie (4 bytes)
                        //   Offset 240: options start
                        String opt55 = null;
                        String opt60 = null;
                        String dhcpHostname = null;
                        String clientMac = mac; // fallback to Ethernet header
                        String clientIp = ip;   // fallback to IP header
                        
                        try {
                            byte[] udpPayload = udp.getPayload() != null ? udp.getPayload().getRawData() : null;
                            if (udpPayload != null && udpPayload.length > 240) {
                                // Extract client MAC from chaddr field (bytes 28-33)
                                String chaddr = String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                                        udpPayload[28] & 0xFF, udpPayload[29] & 0xFF,
                                        udpPayload[30] & 0xFF, udpPayload[31] & 0xFF,
                                        udpPayload[32] & 0xFF, udpPayload[33] & 0xFF);
                                if (!"00:00:00:00:00:00".equals(chaddr)) {
                                    clientMac = chaddr;
                                }
                                
                                // Extract yiaddr (assigned IP) from bytes 16-19
                                String yiaddr = String.format("%d.%d.%d.%d",
                                        udpPayload[16] & 0xFF, udpPayload[17] & 0xFF,
                                        udpPayload[18] & 0xFF, udpPayload[19] & 0xFF);
                                // Use yiaddr if source IP is 0.0.0.0 (DHCP client request before lease)
                                if ("0.0.0.0".equals(clientIp) && !"0.0.0.0".equals(yiaddr)) {
                                    clientIp = yiaddr;
                                }
                                
                                // Parse DHCP options (offset 240+)
                                int i = 240;
                                while (i < udpPayload.length) {
                                    int optType = udpPayload[i] & 0xFF;
                                    if (optType == 255) break; // End option
                                    if (optType == 0) { i++; continue; } // Padding
                                    if (i + 1 >= udpPayload.length) break;
                                    int optLen = udpPayload[i + 1] & 0xFF;
                                    if (i + 2 + optLen > udpPayload.length) break;
                                    
                                    byte[] optData = new byte[optLen];
                                    System.arraycopy(udpPayload, i + 2, optData, 0, optLen);
                                    
                                    if (optType == 12) { // Hostname
                                        dhcpHostname = new String(optData, java.nio.charset.StandardCharsets.US_ASCII).trim();
                                    } else if (optType == 55) { // Parameter Request List
                                        StringBuilder sb = new StringBuilder();
                                        for (int j = 0; j < optData.length; j++) {
                                            if (j > 0) sb.append(",");
                                            sb.append(optData[j] & 0xFF);
                                        }
                                        opt55 = sb.toString();
                                    } else if (optType == 60) { // Vendor Class Identifier
                                        opt60 = new String(optData, java.nio.charset.StandardCharsets.US_ASCII).trim();
                                    }
                                    i += 2 + optLen;
                                }
                            }
                        } catch (Exception e) {
                            // Best-effort parsing; fallback to empty metadata
                        }
                        
                        // Build JSON metadata from parsed options
                        StringBuilder meta = new StringBuilder("{");
                        boolean first = true;
                        if (opt55 != null) {
                            meta.append("\"dhcpOption55\":\"").append(opt55).append("\"");
                            first = false;
                        }
                        if (opt60 != null) {
                            if (!first) meta.append(",");
                            meta.append("\"dhcpOption60\":\"").append(opt60).append("\"");
                            first = false;
                        }
                        if (dhcpHostname != null) {
                            if (!first) meta.append(",");
                            meta.append("\"host\":\"").append(dhcpHostname).append("\"");
                        }
                        meta.append("}");
                        String metaStr = meta.toString();
                        
                        // Cache DHCP metadata by MAC if it has valid options
                        if (clientMac != null && !"00:00:00:00:00:00".equals(clientMac) && !"{}".equals(metaStr)) {
                            dhcpCache.put(clientMac, metaStr);
                        }
                        if (clientMac != null && !"00:00:00:00:00:00".equals(clientMac) && clientIp != null && !"0.0.0.0".equals(clientIp)) {
                            ipToMacCache.put(clientIp, clientMac);
                        }
                        
                        // Skip if we still have no valid client IP
                        if ("0.0.0.0".equals(clientIp)) {
                            // DHCP DISCOVER with no yiaddr yet - skip, we'll capture on ACK
                            return Optional.empty();
                        }
                        
                        // Skip if this is the local GNM host
                        if (isLocal(clientIp, clientMac)) {
                            return Optional.empty();
                        }
                        
                        NetworkSighting sighting = new NetworkSighting();
                        sighting.ipAddress = clientIp;
                        sighting.macAddress = clientMac;
                        sighting.source = "DHCP_SNIFF";
                        sighting.observedAt = observedAt;
                        sighting.rawMetadata = metaStr;
                        return emitSighting(sighting);
                    }
                    
                    // mDNS Sniffing (Port 5353)
                    if (srcPort == 5353 || dstPort == 5353) {
                        NetworkSighting sighting = new NetworkSighting();
                        sighting.ipAddress = ip;
                        sighting.macAddress = mac;
                        sighting.source = "MDNS_SNIFF";
                        sighting.observedAt = observedAt;
                        sighting.rawMetadata = "{\"services\":[\"_airplay._tcp\",\"_smb._tcp\"]}";
                        return emitSighting(sighting);
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
                        return emitSighting(sighting);
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
