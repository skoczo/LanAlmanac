package com.gnm.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashMap;

import com.gnm.dto.backup.*;
import com.gnm.model.*;

@ApplicationScoped
public class BackupService {

    public static final String CURRENT_BACKUP_VERSION = "4";

    @Transactional
    public LanAlmanacBackup exportData(boolean includeSecrets) {
        LanAlmanacBackup backup = new LanAlmanacBackup();
        backup.version = CURRENT_BACKUP_VERSION;
        backup.exportDate = Instant.now();

        List<PhysicalDevice> devices = PhysicalDevice.listAll();
        backup.devices = devices.stream().map(d -> mapToBackup(d, includeSecrets)).collect(Collectors.toList());

        List<NetworkLink> links = NetworkLink.listAll();
        backup.links = links.stream().map(this::mapToBackup).collect(Collectors.toList());

        return backup;
    }

    @Transactional
    public void importData(LanAlmanacBackup backup) {
        // Wipe existing data
        NetworkLink.deleteAll();
        PhysicalDevice.deleteAll(); // Cascade will delete credentials, identities, fingerprints
        PhysicalDevice.getEntityManager().flush();
        
        Map<UUID, PhysicalDevice> deviceMap = new HashMap<>();

        // Insert new data
        if (backup.devices != null) {
            for (PhysicalDeviceBackup db : backup.devices) {
                UUID oldId = db.id;
                db.id = null; // Clear ID so Hibernate treats it as new
                PhysicalDevice device = mapFromBackup(db);
                device.persist();
                deviceMap.put(oldId, device);
            }
        }

        if (backup.links != null) {
            for (NetworkLinkBackup lb : backup.links) {
                lb.id = null;
                NetworkLink link = mapFromBackup(lb);
                if (lb.sourceDeviceId != null && deviceMap.containsKey(lb.sourceDeviceId)) {
                    link.sourceDevice = deviceMap.get(lb.sourceDeviceId);
                } else {
                    link.sourceDevice = null;
                }
                if (lb.targetDeviceId != null && deviceMap.containsKey(lb.targetDeviceId)) {
                    link.targetDevice = deviceMap.get(lb.targetDeviceId);
                } else {
                    link.targetDevice = null;
                }
                link.persist();
            }
        }
    }

    // --- Mapping to Backup DTOs ---

    private PhysicalDeviceBackup mapToBackup(PhysicalDevice device, boolean includeSecrets) {
        PhysicalDeviceBackup dto = new PhysicalDeviceBackup();
        dto.id = device.id;
        dto.displayName = device.displayName;
        dto.deviceType = device.deviceType;
        dto.osFamily = device.osFamily;
        dto.osVersion = device.osVersion;
        dto.manufacturer = device.manufacturer;
        dto.model = device.model;
        dto.locationNote = device.locationNote;
        dto.confidenceScore = device.confidenceScore;
        dto.manuallyVerified = device.manuallyVerified;
        dto.firstSeen = device.firstSeen;
        dto.lastSeen = device.lastSeen;
        dto.status = device.status;
        dto.managementState = device.managementState;
        if (device.labels != null) dto.labels.addAll(device.labels);
        
        if (device.identities != null) {
            dto.identities = device.identities.stream().map(this::mapToBackup).collect(Collectors.toList());
        }
        if (device.fingerprints != null) {
            dto.fingerprints = device.fingerprints.stream().map(this::mapToBackup).collect(Collectors.toList());
        }
        if (device.credentials != null) {
            dto.credentials = device.credentials.stream().map(c -> mapToBackup(c, includeSecrets)).collect(Collectors.toList());
        }
        return dto;
    }

    private NetworkIdentityBackup mapToBackup(NetworkIdentity id) {
        NetworkIdentityBackup dto = new NetworkIdentityBackup();
        dto.id = id.id;
        dto.ipAddress = id.ipAddress;
        dto.macAddress = id.macAddress;
        dto.hostname = id.hostname;
        dto.dhcpLeaseId = id.dhcpLeaseId;
        dto.firstSeen = id.firstSeen;
        dto.lastSeen = id.lastSeen;
        dto.current = id.current;
        return dto;
    }

    private FingerprintVectorBackup mapToBackup(FingerprintVector f) {
        FingerprintVectorBackup dto = new FingerprintVectorBackup();
        dto.id = f.id;
        dto.version = f.version;
        dto.dhcpOption55 = f.dhcpOption55;
        dto.dhcpOption60 = f.dhcpOption60;
        dto.tcpFingerprint = f.tcpFingerprint;
        if (f.mdnsServices != null) dto.mdnsServices = String.join(",", f.mdnsServices);
        dto.ssdpUsn = f.ssdpUsn;
        dto.sshBanner = f.sshBanner;
        dto.httpServerHeader = f.httpServerHeader;
        dto.tlsJa4 = f.tlsJa4;
        dto.tlsCertSubject = f.tlsCertSubject;
        if (f.openPorts != null) {
            dto.openPorts = f.openPorts.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        dto.macOui = f.macOui;
        dto.capturedAt = f.capturedAt;
        return dto;
    }

    private CredentialBackup mapToBackup(Credential c, boolean includeSecrets) {
        CredentialBackup dto = new CredentialBackup();
        dto.id = c.id;
        dto.label = c.label;
        dto.credentialType = c.credentialType;
        dto.username = c.username;
        dto.port = c.port;
        dto.createdAt = c.createdAt;
        dto.updatedAt = c.updatedAt;
        if (includeSecrets) {
            dto.encryptedPayload = c.encryptedPayload;
            dto.noncePayload = c.noncePayload;
        }
        return dto;
    }

    private NetworkLinkBackup mapToBackup(NetworkLink link) {
        NetworkLinkBackup dto = new NetworkLinkBackup();
        dto.id = link.id;
        dto.sourceDeviceId = link.sourceDevice != null ? link.sourceDevice.id : null;
        dto.targetDeviceId = link.targetDevice != null ? link.targetDevice.id : null;
        dto.sourceInterface = link.sourceInterface;
        dto.targetInterface = link.targetInterface;
        dto.discoveryProtocol = link.discoveryProtocol;
        dto.lastVerified = link.lastVerified;
        return dto;
    }

    // --- Mapping from Backup DTOs ---

    private PhysicalDevice mapFromBackup(PhysicalDeviceBackup dto) {
        PhysicalDevice device = new PhysicalDevice();
        // Skip setting ID manually so @GeneratedValue works
        device.displayName = dto.displayName;
        device.deviceType = dto.deviceType;
        device.osFamily = dto.osFamily;
        device.osVersion = dto.osVersion;
        device.manufacturer = dto.manufacturer;
        device.model = dto.model;
        device.locationNote = dto.locationNote;
        device.confidenceScore = dto.confidenceScore != null ? dto.confidenceScore : 1.0;
        device.manuallyVerified = dto.manuallyVerified != null ? dto.manuallyVerified : false;
        device.firstSeen = dto.firstSeen;
        device.lastSeen = dto.lastSeen;
        device.status = dto.status;
        device.managementState = dto.managementState;
        if (dto.labels != null) device.labels.addAll(dto.labels);

        if (dto.identities != null) {
            for (NetworkIdentityBackup idDto : dto.identities) {
                NetworkIdentity id = mapFromBackup(idDto);
                id.physicalDevice = device;
                device.identities.add(id);
            }
        }
        if (dto.fingerprints != null) {
            for (FingerprintVectorBackup fpDto : dto.fingerprints) {
                FingerprintVector fp = mapFromBackup(fpDto);
                fp.physicalDevice = device;
                device.fingerprints.add(fp);
            }
        }
        if (dto.credentials != null) {
            for (CredentialBackup credDto : dto.credentials) {
                Credential cred = mapFromBackup(credDto);
                cred.physicalDevice = device;
                device.credentials.add(cred);
            }
        }
        return device;
    }

    private NetworkIdentity mapFromBackup(NetworkIdentityBackup dto) {
        NetworkIdentity id = new NetworkIdentity();
        // Skip setting ID manually
        id.ipAddress = dto.ipAddress;
        id.macAddress = dto.macAddress;
        id.hostname = dto.hostname;
        id.dhcpLeaseId = dto.dhcpLeaseId;
        id.firstSeen = dto.firstSeen;
        id.lastSeen = dto.lastSeen;
        id.current = dto.current;
        return id;
    }

    private FingerprintVector mapFromBackup(FingerprintVectorBackup dto) {
        FingerprintVector fp = new FingerprintVector();
        // Skip setting ID manually
        fp.version = dto.version;
        fp.dhcpOption55 = dto.dhcpOption55;
        fp.dhcpOption60 = dto.dhcpOption60;
        fp.tcpFingerprint = dto.tcpFingerprint;
        if (dto.mdnsServices != null && !dto.mdnsServices.isEmpty()) {
            fp.mdnsServices = List.of(dto.mdnsServices.split(","));
        }
        fp.ssdpUsn = dto.ssdpUsn;
        fp.sshBanner = dto.sshBanner;
        fp.httpServerHeader = dto.httpServerHeader;
        fp.tlsJa4 = dto.tlsJa4;
        fp.tlsCertSubject = dto.tlsCertSubject;
        if (dto.openPorts != null && !dto.openPorts.isEmpty()) {
            fp.openPorts = java.util.Arrays.stream(dto.openPorts.split(","))
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
        }
        fp.macOui = dto.macOui;
        fp.capturedAt = dto.capturedAt;
        return fp;
    }

    private Credential mapFromBackup(CredentialBackup dto) {
        Credential c = new Credential();
        // Skip setting ID manually
        c.label = dto.label;
        c.credentialType = dto.credentialType;
        c.username = dto.username;
        c.port = dto.port;
        c.createdAt = dto.createdAt;
        c.updatedAt = dto.updatedAt;
        c.encryptedPayload = dto.encryptedPayload != null ? dto.encryptedPayload : new byte[0];
        c.noncePayload = dto.noncePayload != null ? dto.noncePayload : new byte[0];
        return c;
    }

    private NetworkLink mapFromBackup(NetworkLinkBackup dto) {
        NetworkLink link = new NetworkLink();
        // Skip setting ID manually
        
        if (dto.sourceDeviceId != null) {
            link.sourceDevice = PhysicalDevice.findById(dto.sourceDeviceId);
        }
        if (dto.targetDeviceId != null) {
            link.targetDevice = PhysicalDevice.findById(dto.targetDeviceId);
        }
        
        link.sourceInterface = dto.sourceInterface;
        link.targetInterface = dto.targetInterface;
        link.discoveryProtocol = dto.discoveryProtocol;
        link.lastVerified = dto.lastVerified;
        return link;
    }
}
