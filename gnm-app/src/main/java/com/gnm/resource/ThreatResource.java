package com.gnm.resource;

import com.gnm.model.ThreatEvent;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.gnm.model.FingerprintVector;
import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.enums.DeviceType;
import com.gnm.model.enums.DeviceStatus;
import java.time.Instant;

@Path("/api/threats")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ThreatResource {

    @GET
    @Transactional
    public List<ThreatEvent> getThreats() {
        List<ThreatEvent> threats = ThreatEvent.list("ORDER BY detectedAt DESC");
        for (ThreatEvent threat : threats) {
            if (threat.macAddress != null && !threat.macAddress.isEmpty()) {
                NetworkIdentity identity = NetworkIdentity.find("macAddress = ?1", threat.macAddress).firstResult();
                if (identity != null && identity.physicalDevice != null) {
                    boolean updated = false;
                    if (threat.physicalDeviceId == null) {
                        threat.physicalDeviceId = identity.physicalDevice.id;
                        updated = true;
                    }
                    if (threat.description != null && threat.description.startsWith("Rogue Device Detected") && !threat.resolved) {
                        threat.resolved = true;
                        updated = true;
                    }
                    if (updated) {
                        threat.persist();
                    }
                }
            }
        }
        return threats;
    }

    @PUT
    @Path("/{id}/resolve")
    @Transactional
    public ThreatEvent resolveThreat(@PathParam("id") UUID id) {
        ThreatEvent threat = ThreatEvent.findById(id);
        if (threat != null) {
            threat.resolved = true;
            threat.persist();
        }
        return threat;
    }

    @PUT
    @Path("/{id}/unresolve")
    @Transactional
    public ThreatEvent unresolveThreat(@PathParam("id") UUID id) {
        ThreatEvent threat = ThreatEvent.findById(id);
        if (threat != null) {
            threat.resolved = false;
            threat.persist();
        }
        return threat;
    }

    @PUT
    @Path("/{id}/note")
    @Transactional
    public ThreatEvent updateNote(@PathParam("id") UUID id, Map<String, String> payload) {
        ThreatEvent threat = ThreatEvent.findById(id);
        if (threat != null && payload.containsKey("notes")) {
            threat.notes = payload.get("notes");
            threat.persist();
        }
        return threat;
    }

    @PUT
    @Path("/{id}/accept-ssh-key")
    @Transactional
    public ThreatEvent acceptSshKey(@PathParam("id") UUID id) {
        ThreatEvent threat = ThreatEvent.findById(id);
        if (threat != null && !threat.resolved) {
            String desc = threat.description;
            String keyMarker = "Key: ";
            if (desc != null && desc.contains(keyMarker)) {
                String newKey = desc.substring(desc.lastIndexOf(keyMarker) + keyMarker.length()).trim();
                if (threat.physicalDeviceId != null) {
                    // Find the primary fingerprint vector (version 1)
                    FingerprintVector fp = FingerprintVector.find("physicalDevice.id = ?1 and version = 1", threat.physicalDeviceId).firstResult();
                    if (fp != null) {
                        if (fp.sshHostKeys == null) {
                            fp.sshHostKeys = new java.util.ArrayList<>();
                        }
                        if (!fp.sshHostKeys.contains(newKey)) {
                            fp.sshHostKeys.add(newKey);
                            fp.persist();
                        }
                    }
                    // Also update any NetworkService for this device that provides SSH
                    List<NetworkService> services = NetworkService.list("physicalDevice.id = ?1 and serviceType = 'SSH'", threat.physicalDeviceId);
                    for (NetworkService s : services) {
                        s.sshHostKey = newKey;
                        s.sshHostKeyTrusted = true;
                        s.persist();
                    }
                }
            }
            threat.resolved = true;
            threat.persist();
        }
        return threat;
    }

    @POST
    @Path("/{id}/approve-device")
    @Transactional
    public ThreatEvent approveDevice(@PathParam("id") UUID id, Map<String, String> payload) {
        ThreatEvent threat = ThreatEvent.findById(id);
        if (threat != null) {
            PhysicalDevice targetDevice = null;

            if (threat.physicalDeviceId != null) {
                targetDevice = PhysicalDevice.findById(threat.physicalDeviceId);
            }

            if (targetDevice == null && threat.macAddress != null && !threat.macAddress.isEmpty()) {
                NetworkIdentity existingId = NetworkIdentity.find("macAddress = ?1", threat.macAddress).firstResult();
                if (existingId != null) {
                    targetDevice = existingId.physicalDevice;
                }
            }

            String customName = payload != null ? payload.get("displayName") : null;

            if (targetDevice != null) {
                if (customName != null && !customName.trim().isEmpty()) {
                    targetDevice.displayName = customName.trim();
                    targetDevice.persist();
                }
            } else {
                PhysicalDevice newDevice = new PhysicalDevice();
                newDevice.deviceType = DeviceType.UNKNOWN;
                newDevice.firstSeen = threat.detectedAt != null ? threat.detectedAt : Instant.now();
                newDevice.lastSeen = newDevice.firstSeen;
                newDevice.status = DeviceStatus.ONLINE;
                newDevice.confidenceScore = 1.0;

                if (customName != null && !customName.trim().isEmpty()) {
                    newDevice.displayName = customName.trim();
                } else {
                    newDevice.displayName = "Approved Device: " + (threat.ipAddress != null ? threat.ipAddress : "Unknown");
                }

                String desc = threat.description;
                if (desc != null && desc.contains("from ")) {
                    String hostname = desc.substring(desc.indexOf("from ") + 5).trim();
                    if (!hostname.equalsIgnoreCase("Unknown") && (customName == null || customName.trim().isEmpty())) {
                        newDevice.displayName = hostname;
                    }
                }
                newDevice.persistAndFlush();

                NetworkIdentity newId = new NetworkIdentity();
                newId.physicalDevice = newDevice;
                newId.ipAddress = threat.ipAddress;
                newId.macAddress = threat.macAddress;
                newId.firstSeen = newDevice.firstSeen;
                newId.lastSeen = newDevice.lastSeen;
                newId.current = true;

                if (desc != null && desc.contains("from ")) {
                    String hostname = desc.substring(desc.indexOf("from ") + 5).trim();
                    if (!hostname.equalsIgnoreCase("Unknown")) {
                        newId.hostname = hostname;
                    }
                }

                newId.persistAndFlush();
                targetDevice = newDevice;
            }

            if (targetDevice != null) {
                threat.physicalDeviceId = targetDevice.id;
                threat.resolved = true;
                threat.persist();

                // Link and resolve all other threats matching this MAC address
                if (threat.macAddress != null && !threat.macAddress.isEmpty()) {
                    List<ThreatEvent> matching = ThreatEvent.list("macAddress = ?1", threat.macAddress);
                    for (ThreatEvent t : matching) {
                        t.physicalDeviceId = targetDevice.id;
                        t.resolved = true;
                        t.persist();
                    }
                }
            }
        }
        return threat;
    }
}
