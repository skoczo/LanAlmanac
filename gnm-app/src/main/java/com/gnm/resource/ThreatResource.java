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
            // Rogue device threats must NEVER have physicalDeviceId auto-assigned from
            // existing NetworkIdentity entries. The rogue device is flagged precisely
            // because it does NOT belong to any known baseline device. Assigning an
            // existing device's ID here would corrupt the UI (hide "Add to Baseline"
            // button) and could allow users to accidentally rename a legitimate device.
            boolean isRogueDevice = threat.description != null && threat.description.startsWith("Rogue Device Detected");

            if (isRogueDevice) {
                // Self-healing: clear any physicalDeviceId that was incorrectly written
                // to the DB by the old buggy eager-assignment logic, but only for
                // unresolved threats. Resolved rogue threats keep their physicalDeviceId
                // (it points to the device the admin deliberately approved via approve-device).
                if (!threat.resolved && threat.physicalDeviceId != null) {
                    threat.physicalDeviceId = null;
                    threat.persist();
                }
            } else if (threat.physicalDeviceId == null) {
                if (threat.macAddress != null && !threat.macAddress.isEmpty()) {
                    NetworkIdentity identity = NetworkIdentity.find("macAddress = ?1", threat.macAddress).firstResult();
                    if (identity != null && identity.physicalDevice != null) {
                        threat.physicalDeviceId = identity.physicalDevice.id;
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
            // For rogue device threats: clear the physicalDeviceId so the
            // "Add Device" flow becomes fully available again (the previously
            // approved device is NOT deleted — it stays in the baseline).
            boolean isRogueDevice = threat.description != null && threat.description.startsWith("Rogue Device Detected");
            if (isRogueDevice) {
                threat.physicalDeviceId = null;
            }
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
            boolean isRogueDevice = threat.description != null && threat.description.startsWith("Rogue Device Detected");
            PhysicalDevice targetDevice = null;

            // For rogue device threats we must ALWAYS create a new device.
            // Never link a rogue sighting to an existing baseline device by MAC/IP.
            if (!isRogueDevice) {
                if (threat.physicalDeviceId != null) {
                    targetDevice = PhysicalDevice.findById(threat.physicalDeviceId);
                }

                if (targetDevice == null && threat.macAddress != null && !threat.macAddress.isEmpty()) {
                    NetworkIdentity existingId = NetworkIdentity.find("macAddress = ?1", threat.macAddress).firstResult();
                    if (existingId != null) {
                        targetDevice = existingId.physicalDevice;
                    }
                }
            }

            if (targetDevice != null && payload != null && payload.containsKey("displayName")) {
                String customName = payload.get("displayName");
                if (customName != null && !customName.trim().isEmpty()) {
                    targetDevice.displayName = customName.trim();
                    targetDevice.persist();
                }
            }

            if (targetDevice == null) {
                PhysicalDevice newDevice = new PhysicalDevice();
                newDevice.deviceType = DeviceType.UNKNOWN;
                newDevice.firstSeen = threat.detectedAt != null ? threat.detectedAt : Instant.now();
                newDevice.lastSeen = newDevice.firstSeen;
                newDevice.status = DeviceStatus.ONLINE;
                newDevice.confidenceScore = 1.0;

                // Use user-provided display name first, then hostname from description, then IP fallback
                String customName = payload != null ? payload.get("displayName") : null;
                if (customName != null && !customName.trim().isEmpty()) {
                    newDevice.displayName = customName.trim();
                } else {
                    newDevice.displayName = "Approved from IDS Alert: " + (threat.ipAddress != null ? threat.ipAddress : "Unknown");
                }
                newDevice.persistAndFlush();

                NetworkIdentity newId = new NetworkIdentity();
                newId.physicalDevice = newDevice;
                newId.ipAddress = threat.ipAddress;
                newId.macAddress = threat.macAddress;
                newId.firstSeen = newDevice.firstSeen;
                newId.lastSeen = newDevice.lastSeen;
                newId.current = true;

                String desc = threat.description;
                if (desc.contains("from ")) {
                    String hostname = desc.substring(desc.indexOf("from ") + 5).trim();
                    if (!hostname.equalsIgnoreCase("Unknown")) {
                        newId.hostname = hostname;
                        // Only use hostname as displayName if user didn't provide a custom name
                        if (customName == null || customName.trim().isEmpty()) {
                            newDevice.displayName = hostname;
                        }
                    }
                }

                newId.persistAndFlush();
                targetDevice = newDevice;
            }

            if (targetDevice != null) {
                threat.physicalDeviceId = targetDevice.id;
                threat.resolved = true;
                threat.persist();

                // Link all other threats for the same MAC or IP
                if (threat.macAddress != null && !threat.macAddress.isEmpty()) {
                    List<ThreatEvent> matching = ThreatEvent.list("macAddress = ?1 and physicalDeviceId is null", threat.macAddress);
                    for (ThreatEvent t : matching) {
                        t.physicalDeviceId = targetDevice.id;
                        t.persist();
                    }
                } else if (threat.ipAddress != null && !threat.ipAddress.isEmpty()) {
                    List<ThreatEvent> matching = ThreatEvent.list("ipAddress = ?1 and physicalDeviceId is null", threat.ipAddress);
                    for (ThreatEvent t : matching) {
                        t.physicalDeviceId = targetDevice.id;
                        t.persist();
                    }
                }
            }
        }
        return threat;
    }
}
