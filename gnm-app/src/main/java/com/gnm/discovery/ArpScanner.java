package com.gnm.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.pcap4j.core.BpfProgram;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.Pcaps;
import org.pcap4j.packet.ArpPacket;
import org.pcap4j.packet.EthernetPacket;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.namednumber.ArpHardwareType;
import org.pcap4j.packet.namednumber.ArpOperation;
import org.pcap4j.packet.namednumber.EtherType;
import org.pcap4j.util.ByteArrays;
import org.pcap4j.util.MacAddress;

import com.gnm.model.NetworkSighting;
import com.gnm.model.GlobalSetting;

@ApplicationScoped
public class ArpScanner {

    private static final Logger LOG = Logger.getLogger(ArpScanner.class);

    @Inject
    private NetworkSightingQueue sightingQueue;

    @Inject
    private com.gnm.service.SubnetFilter subnetFilter;

    @ConfigProperty(name = "gnm.listen.interface", defaultValue = "eth0")
    private String networkInterfaceProp;

    public Set<String> scan() {
        String networkInterface = getListenInterface();
        LOG.info("Starting active ARP scan on interface: " + networkInterface);

        try {
            return runPcapArpScan();
        } catch (Throwable e) {
            LOG.info("Raw socket ARP scan: " + e.getMessage() + ". Using system ARP cache fallback.");
            return runArpCacheFallback();
        }
    }

    private Set<String> runPcapArpScan() throws Exception {
        String ifaceName = getListenInterface();
        LOG.info("Checking raw socket privileges for active PCAP ARP scan on " + ifaceName + "...");

        PcapNetworkInterface nif;
        try {
            nif = Pcaps.getDevByName(ifaceName);
        } catch (Throwable t) {
            throw new UnsupportedOperationException(
                    "Libpcap native library or dev interface unavailable: " + t.getMessage(), t);
        }

        if (nif == null) {
            throw new UnsupportedOperationException(
                    "Network interface " + ifaceName + " not found in libpcap device list.");
        }

        NetworkInterface netIf = NetworkInterface.getByName(ifaceName);
        if (netIf == null) {
            throw new UnsupportedOperationException("Java NetworkInterface " + ifaceName + " unavailable.");
        }

        MacAddress localMac = getLocalMacAddress(netIf);
        Inet4Address localIp = getLocalInet4Address(netIf);

        PcapHandle handle = null;
        Set<String> liveIps = new HashSet<>();

        try {
            // Attempt to open live capture handle - tests NET_RAW / NET_ADMIN capabilities
            handle = nif.openLive(65535, PcapNetworkInterface.PromiscuousMode.PROMISCUOUS, 10);

            // Set BPF filter to capture ARP replies only
            handle.setFilter("arp and arp[7] == 2", BpfProgram.BpfCompileMode.OPTIMIZE);

            LOG.info("Raw socket privileges confirmed. Broadcasting ARP probes on " + ifaceName + "...");

            List<Inet4Address> targetIps = getTargetIps(localIp);
            for (Inet4Address targetIp : targetIps) {
                ArpPacket.Builder arpBuilder = new ArpPacket.Builder();
                arpBuilder
                        .hardwareType(ArpHardwareType.ETHERNET)
                        .protocolType(EtherType.IPV4)
                        .hardwareAddrLength((byte) MacAddress.SIZE_IN_BYTES)
                        .protocolAddrLength((byte) ByteArrays.INET4_ADDRESS_SIZE_IN_BYTES)
                        .operation(ArpOperation.REQUEST)
                        .srcHardwareAddr(localMac)
                        .srcProtocolAddr(localIp)
                        .dstHardwareAddr(MacAddress.ETHER_BROADCAST_ADDRESS)
                        .dstProtocolAddr(targetIp);

                EthernetPacket.Builder etherBuilder = new EthernetPacket.Builder();
                etherBuilder
                        .dstAddr(MacAddress.ETHER_BROADCAST_ADDRESS)
                        .srcAddr(localMac)
                        .type(EtherType.ARP)
                        .payloadBuilder(arpBuilder)
                        .paddingAtBuild(true);

                Packet packet = etherBuilder.build();
                handle.sendPacket(packet);
            }

            // Receive replies for up to 1 second
            long startTime = System.currentTimeMillis();
            int count = 0;
            while (System.currentTimeMillis() - startTime < 1000) {
                Packet replyPacket = handle.getNextPacket();
                if (replyPacket != null && replyPacket.contains(ArpPacket.class)) {
                    ArpPacket arp = replyPacket.get(ArpPacket.class);
                    if (arp.getHeader().getOperation() == ArpOperation.REPLY) {
                        String ip = arp.getHeader().getSrcProtocolAddr().getHostAddress();
                        String mac = arp.getHeader().getSrcHardwareAddr().toString().toUpperCase();

                        if (subnetFilter.isIpInSubnet(ip) && !"00:00:00:00:00:00".equals(mac)) {
                            liveIps.add(ip);
                            NetworkSighting sighting = new NetworkSighting();
                            sighting.ipAddress = ip;
                            sighting.macAddress = mac;
                            sighting.source = "ACTIVE_ARP_PCAP";
                            sighting.observedAt = Instant.now();
                            sighting.rawMetadata = "{\"method\":\"pcap_active_arp\"}";

                            sightingQueue.offer(sighting);
                            count++;
                        }
                    }
                }
            }
            LOG.info("Native PCAP ARP scan completed. Discovered " + count + " IP/MAC pairs.");
        } finally {
            if (handle != null && handle.isOpen()) {
                try {
                    handle.close();
                } catch (Exception ignored) {
                }
            }
        }
        return liveIps;
    }

    private MacAddress getLocalMacAddress(NetworkInterface netIf) throws Exception {
        byte[] hardwareAddress = netIf.getHardwareAddress();
        if (hardwareAddress == null || hardwareAddress.length != 6) {
            throw new IllegalArgumentException("Interface does not have a valid 6-byte MAC address");
        }
        return MacAddress.getByAddress(hardwareAddress);
    }

    private Inet4Address getLocalInet4Address(NetworkInterface netIf) throws Exception {
        Enumeration<InetAddress> addrs = netIf.getInetAddresses();
        while (addrs.hasMoreElements()) {
            InetAddress addr = addrs.nextElement();
            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                return (Inet4Address) addr;
            }
        }
        throw new IllegalArgumentException("Interface does not have a valid IPv4 address");
    }

    private List<Inet4Address> getTargetIps(Inet4Address localIp) {
        List<Inet4Address> targets = new ArrayList<>();
        try {
            String cidrConfig = getSubnetConfig();
            if (cidrConfig == null || cidrConfig.isBlank()) {
                return targets;
            }

            String[] subnets = cidrConfig.split(",");
            int totalCount = 0;

            for (String subnet : subnets) {
                String trimmed = subnet.trim();
                if (trimmed.isEmpty())
                    continue;

                String[] parts = trimmed.split("/");
                String baseIpStr = parts[0];
                int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : 24;

                InetAddress base = InetAddress.getByName(baseIpStr);
                if (!(base instanceof Inet4Address)) {
                    continue;
                }

                byte[] bytes = base.getAddress();
                int ipInt = ((bytes[0] & 0xFF) << 24) |
                        ((bytes[1] & 0xFF) << 16) |
                        ((bytes[2] & 0xFF) << 8) |
                        (bytes[3] & 0xFF);

                int mask = (prefix == 0) ? 0 : 0xFFFFFFFF << (32 - prefix);
                int networkInt = ipInt & mask;
                int broadcastInt = networkInt | ~mask;

                int startHost = networkInt + 1;
                int endHost = broadcastInt - 1;

                for (int cur = startHost; cur <= endHost && totalCount < 1024; cur++) {
                    byte[] ipBytes = new byte[] {
                            (byte) ((cur >> 24) & 0xFF),
                            (byte) ((cur >> 16) & 0xFF),
                            (byte) ((cur >> 8) & 0xFF),
                            (byte) (cur & 0xFF)
                    };
                    InetAddress addr = InetAddress.getByAddress(ipBytes);
                    if (addr instanceof Inet4Address && !addr.equals(localIp)) {
                        targets.add((Inet4Address) addr);
                    }
                    totalCount++;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to calculate target IPs for active ARP scan", e);
        }
        return targets;
    }

    private String getSubnetConfig() {
        try {
            GlobalSetting setting = GlobalSetting.findById("gnm.subnet");
            if (setting != null && setting.value != null && !setting.value.trim().isEmpty()) {
                return setting.value.trim();
            }
        } catch (Exception ignored) {
        }
        return subnetFilter.getSubnetConfig();
    }

    private String getListenInterface() {
        GlobalSetting setting = GlobalSetting.findById("gnm.listen.interface");
        if (setting != null && setting.value != null && !setting.value.trim().isEmpty()) {
            return setting.value.trim();
        }
        return networkInterfaceProp;
    }

    private Set<String> runArpCacheFallback() {
        File arpFile = new File("/proc/net/arp");
        if (!arpFile.exists() || !arpFile.canRead()) {
            LOG.error("Cannot read /proc/net/arp. System ARP table fallback unavailable.");
            return Collections.emptySet();
        }

        Set<String> liveIps = new HashSet<>();

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

                    // Filter out header placeholders, invalid/incomplete entries (0x0 flags), and
                    // IPs outside gnm.subnet
                    if (!"00:00:00:00:00:00".equals(mac) && !"0x0".equals(flags) && mac.contains(":")
                            && subnetFilter.isIpInSubnet(ip)) {
                        liveIps.add(ip);
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
        return liveIps;
    }
}
