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

@Path("/api/threats")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ThreatResource {

    @GET
    public List<ThreatEvent> getThreats() {
        return ThreatEvent.list("ORDER BY detectedAt DESC");
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
}
