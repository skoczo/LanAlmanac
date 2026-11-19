package com.gnm.pkgmgmt.profile;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.client.PkgWorkerClient;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.ProbeResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class OsDiscoveryService {

    private final PkgWorkerClient workerClient;
    private final OsProfileMatcher matcher;

    @Inject
    public OsDiscoveryService(PkgWorkerClient workerClient, OsProfileMatcher matcher) {
        this.workerClient = workerClient;
        this.matcher = matcher;
    }

    @Transactional
    public OsProfile detectAndAssign(PhysicalDevice device) {
        List<OsProfile> activeProfiles = OsProfile.list("enabled = true order by priority asc");
        return detectAndAssign(device, activeProfiles);
    }

    public OsProfile detectAndAssign(PhysicalDevice device, List<OsProfile> activeProfiles) {
        if (device == null) {
            throw new IllegalArgumentException("device is required");
        }
        if (device.osProfilePinned && device.osProfile != null) {
            return device.osProfile;
        }

        Map<String, String> verifyCommands = new HashMap<>();
        if (activeProfiles != null) {
            for (OsProfile p : activeProfiles) {
                if (p != null
                        && p.enabled
                        && p.detectionRules != null
                        && p.detectionRules.verifyCommand() != null
                        && !p.detectionRules.verifyCommand().isBlank()) {
                    verifyCommands.put(p.slug, p.detectionRules.verifyCommand());
                }
            }
        }

        ProbeResponse probe = workerClient.probeDevice(device, verifyCommands).response();
        Map<String, String> facts = probe.osFacts() != null ? probe.osFacts() : Map.of();
        Map<String, Boolean> verifyResults =
                probe.verifyResults() != null ? probe.verifyResults() : Map.of();

        device.osFacts = facts;
        device.osDetectedAt = Instant.now();

        String osId = facts.get("os.id");
        if (osId != null && !osId.isBlank()) {
            device.osFamily =
                    osId.substring(0, 1).toUpperCase(Locale.ROOT) + osId.substring(1);
        }
        String versionId = facts.get("os.version_id");
        if (versionId == null || versionId.isBlank()) {
            versionId = facts.get("os.version_major");
        }
        if (versionId != null && !versionId.isBlank()) {
            device.osVersion = versionId;
        }

        Optional<OsProfile> matched = matcher.findMatch(activeProfiles, facts, verifyResults);
        device.osProfile = matched.orElse(null);
        return device.osProfile;
    }
}
