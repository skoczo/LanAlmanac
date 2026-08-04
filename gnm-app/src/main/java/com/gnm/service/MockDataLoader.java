package com.gnm.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import com.gnm.model.*;
import com.gnm.model.enums.*;

@ApplicationScoped
public class MockDataLoader {

    private static final Logger LOG = Logger.getLogger(MockDataLoader.class);

    @Transactional
    public void onStart(@Observes StartupEvent ev) {
        if (PhysicalDevice.count() > 0) {
            LOG.info("Database already contains device data. Skipping mock data generation.");
            return;
        }

        LOG.info("Initializing mock network discovery data...");

        Instant now = Instant.now();

        // 1. Gateway Router (UniFi Dream Machine)
        PhysicalDevice gateway = createDevice("UniFi Dream Machine Pro", DeviceType.ROUTER, "UniFi OS", "4.0.6", "Ubiquiti", "UDM-Pro", "Rack 1", 1.0, DeviceStatus.ONLINE, now);
        createIdentity(gateway, "192.168.1.1", "04:18:D6:11:22:33", "gateway.local", true, now);
        createFingerprint(gateway, "1,3,6,12,15,28,42", "ubnt-udm", "TTL=64, WS=65535", List.of("_http._tcp", "_device-info._tcp"), "ssdp:udm", "SSH-2.0-OpenSSH_8.9p1", "nginx", "ja4_udm", "CN=udm.local", List.of(22, 80, 443, 8080, 8443), "04:18:D6", now);
        createCredential(gateway, "SSH Admin", CredentialType.SSH_KEY, "admin", 22, now);
        generateTelemetry(gateway, now);

        // 2. Synology NAS
        PhysicalDevice nas = createDevice("Storage-NAS", DeviceType.NAS, "DSM", "7.2.1", "Synology", "DS920+", "Basement Utility Room", 0.98, DeviceStatus.ONLINE, now);
        createIdentity(nas, "192.168.1.10", "00:11:32:AA:BB:CC", "nas.local", true, now);
        // Previous identity (IP change)
        createIdentity(nas, "192.168.1.12", "00:11:32:AA:BB:CC", "nas-old.local", false, now.minus(5, ChronoUnit.DAYS));
        createFingerprint(nas, "1,3,6,15,28,33,42,119,121", "synology-nas", "TTL=64, WS=14600", List.of("_smb._tcp", "_http._tcp", "_device-info._tcp"), "ssdp:nas", "SSH-2.0-OpenSSH_8.2p1", "Apache/2.4.58", "ja4_nas", "CN=synology.local", List.of(22, 80, 443, 5000, 5001), "00:11:32", now);
        createCredential(nas, "DSM Root", CredentialType.PASSWORD, "root", 22, now);
        generateTelemetry(nas, now);

        // 3. Proxmox Server
        PhysicalDevice proxmox = createDevice("Proxmox-Node-01", DeviceType.SERVER, "Debian (Proxmox PVE)", "8.1.4", "Supermicro", "SYS-E300", "Rack 1", 0.95, DeviceStatus.ONLINE, now);
        createIdentity(proxmox, "192.168.1.20", "3C:8C:F8:99:88:77", "pve1.local", true, now);
        createFingerprint(proxmox, "1,3,6,15,26,28,31,33,43,119", "proxmox-pve", "TTL=64, WS=29200", List.of("_ssh._tcp", "_https._tcp"), "ssdp:pve", "SSH-2.0-OpenSSH_9.2p1", "Apache", "ja4_pve", "CN=pve1.local", List.of(22, 8006), "3C:8C:F8", now);
        createCredential(proxmox, "PVE API Key", CredentialType.API_TOKEN, "pve-admin", 8006, now);
        generateTelemetry(proxmox, now);

        // 4. Living Room Smart TV (LG webOS)
        PhysicalDevice tv = createDevice("Living Room TV", DeviceType.IOT, "webOS", "23.10", "LG Electronics", "OLED65C3", "Living Room", 0.88, DeviceStatus.ONLINE, now);
        createIdentity(tv, "192.168.1.150", "A4:70:D6:12:34:56", "lgwebostv.local", true, now);
        createFingerprint(tv, "1,3,6,12,15,28", "lg-webos-tv", "TTL=64, WS=14600", List.of("_airplay._tcp", "_googlecast._tcp"), "ssdp:lg-tv", null, "webOS/23.10", "ja4_tv", null, List.of(80, 443, 8080, 9999), "A4:70:D6", now);
        generateTelemetry(tv, now);

        // 5. Office Printer (HP LaserJet)
        PhysicalDevice printer = createDevice("HP LaserJet Pro", DeviceType.IOT, "HP JetDirect", "202401", "HP", "M404dn", "Home Office", 0.76, DeviceStatus.OFFLINE, now);
        createIdentity(printer, "192.168.1.200", "40:B4:CD:AA:BB:CC", "hp-printer.local", true, now);
        createFingerprint(printer, "1,3,6,12,15", "hp-printer", "TTL=255, WS=8192", List.of("_ipp._tcp", "_printer._tcp"), "ssdp:printer", null, "HP-HTTP-Server", null, null, List.of(80, 443, 9100), "40:B4:CD", now);
        generateTelemetry(printer, now);

        // 6. iPhone-Anna (Phone with randomized MAC)
        PhysicalDevice iphone = createDevice("iPhone-Anna", DeviceType.PHONE, "iOS", "18.0", "Apple", "iPhone 15 Pro", "Mobile", 0.91, DeviceStatus.ONLINE, now);
        // Current Random MAC
        createIdentity(iphone, "192.168.1.88", "9C:FC:01:AA:BB:CC", "iphone-anna.local", true, now);
        // Day 1 Random MAC (Identities history)
        createIdentity(iphone, "192.168.1.99", "D6:E7:F8:11:22:33", "iphone-anna-temp.local", false, now.minus(24, ChronoUnit.HOURS));
        createFingerprint(iphone, "1,3,6,15,119,252", "apple-ios", "TTL=64, WS=65535", List.of("_airplay._tcp", "_companion-link._tcp"), null, null, null, "ja4_iphone", null, List.of(62078), "9C:FC:01", now);
        generateTelemetry(iphone, now);

        // 7. Developer Workstation (MacBook Pro)
        PhysicalDevice mbp = createDevice("MacBook-Pro-16", DeviceType.WORKSTATION, "macOS", "15.0", "Apple", "MacBook Pro M3", "Home Office", 0.99, DeviceStatus.ONLINE, now);
        createIdentity(mbp, "192.168.1.75", "F4:D4:88:99:00:AA", "mbp-16.local", true, now);
        createFingerprint(mbp, "1,3,6,15,119,252", "apple-macos", "TTL=64, WS=65535", List.of("_ssh._tcp", "_smb._tcp", "_device-info._tcp"), null, "SSH-2.0-OpenSSH_9.6", null, "ja4_mbp", "CN=mbp.local", List.of(22, 445), "F4:D4:88", now);
        createCredential(mbp, "Local SSH Login", CredentialType.PASSWORD, "developer", 22, now);
        generateTelemetry(mbp, now);

        LOG.info("Mock network discovery data successfully created!");
    }

    private PhysicalDevice createDevice(String displayName, DeviceType type, String osFamily, String osVersion, String manufacturer, String model, String location, double score, DeviceStatus status, Instant now) {
        PhysicalDevice d = new PhysicalDevice();
        d.displayName = displayName;
        d.deviceType = type;
        d.osFamily = osFamily;
        d.osVersion = osVersion;
        d.manufacturer = manufacturer;
        d.model = model;
        d.locationNote = location;
        d.confidenceScore = score;
        d.manuallyVerified = false;
        d.firstSeen = now.minus(10, ChronoUnit.DAYS);
        d.lastSeen = now;
        d.status = status;
        d.persist();
        return d;
    }

    private void createIdentity(PhysicalDevice d, String ip, String mac, String hostname, boolean current, Instant time) {
        NetworkIdentity id = new NetworkIdentity();
        id.physicalDevice = d;
        id.ipAddress = ip;
        id.macAddress = mac;
        id.hostname = hostname;
        id.firstSeen = time.minus(24, ChronoUnit.HOURS);
        id.lastSeen = time;
        id.current = current;
        id.persist();

        // Also add a sighting
        NetworkSighting s = new NetworkSighting();
        s.networkIdentity = id;
        s.ipAddress = ip;
        s.macAddress = mac;
        s.source = "MOCK_SCANNER";
        s.observedAt = time;
        s.persist();
    }

    private void createFingerprint(PhysicalDevice d, String opt55, String opt60, String tcp, List<String> mdns, String ssdp, String ssh, String http, String ja4, String cert, List<Integer> openPorts, String macOui, Instant now) {
        FingerprintVector f = new FingerprintVector();
        f.physicalDevice = d;
        f.version = 1;
        f.dhcpOption55 = opt55;
        f.dhcpOption60 = opt60;
        f.tcpFingerprint = tcp;
        f.mdnsServices = mdns;
        f.ssdpUsn = ssdp;
        f.sshBanner = ssh;
        f.httpServerHeader = http;
        f.tlsJa4 = ja4;
        f.tlsCertSubject = cert;
        f.openPorts = openPorts;
        f.macOui = macOui;
        f.capturedAt = now;
        f.persist();
    }

    private void createCredential(PhysicalDevice d, String label, CredentialType type, String username, int port, Instant now) {
        Credential c = new Credential();
        c.physicalDevice = d;
        c.label = label;
        c.credentialType = type;
        c.username = username;
        c.port = port;
        c.encryptedPayload = "MOCK_ENCRYPTED_SECRET".getBytes();
        c.noncePayload = "MOCK_NONCE".getBytes();
        c.createdAt = now.minus(5, ChronoUnit.DAYS);
        c.updatedAt = now;
        c.persist();
    }

    private void generateTelemetry(PhysicalDevice d, Instant now) {
        Random rand = new Random(d.displayName.hashCode());
        // Generate metrics for last 24 hours (hourly intervals)
        for (int i = 24; i >= 0; i--) {
            Instant t = now.minus(i, ChronoUnit.HOURS);

            // CPU metric
            double cpuVal = 5.0 + rand.nextDouble() * 35.0;
            if (d.deviceType == DeviceType.SERVER) cpuVal += 20; // servers work harder
            if (d.status == DeviceStatus.OFFLINE) cpuVal = 0.0;
            saveTelemetry(t, d.id, "cpu_percent", cpuVal);

            // Memory metric
            double memVal = 30.0 + rand.nextDouble() * 20.0;
            if (d.deviceType == DeviceType.NAS) memVal += 15;
            if (d.status == DeviceStatus.OFFLINE) memVal = 0.0;
            saveTelemetry(t, d.id, "mem_percent", memVal);

            // Ping latency (ms)
            double pingVal = 1.0 + rand.nextDouble() * 8.0;
            if (d.status == DeviceStatus.OFFLINE) pingVal = -1.0; // Offline
            saveTelemetry(t, d.id, "ping_ms", pingVal);
        }
    }

    private void saveTelemetry(Instant time, UUID deviceId, String metricName, double value) {
        Telemetry te = new Telemetry();
        te.id = new Telemetry.TelemetryId(time, deviceId, metricName);
        te.value = value;
        te.labels = null;
        te.persist();
    }
}
