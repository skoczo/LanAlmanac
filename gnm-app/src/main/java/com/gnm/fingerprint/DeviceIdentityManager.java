package com.gnm.fingerprint;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.gnm.model.*;
import com.gnm.model.enums.*;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;

@ApplicationScoped
public class DeviceIdentityManager {
    private static final Logger LOG = Logger.getLogger(DeviceIdentityManager.class);
    @Inject Event<FingerprintEngine.DeviceEvent> eventBroadcaster;
    @Inject SimilarityEngine similarityEngine;
    @Inject DeviceIdentityManager self;
    @ConfigProperty(name = "gnm.fingerprint.merge-threshold", defaultValue = "0.75") Double mergeThreshold;
    private final java.util.concurrent.locks.ReentrantLock dbLock = new java.util.concurrent.locks.ReentrantLock();
    public void lock() { dbLock.lock(); }
    public void unlock() { dbLock.unlock(); }

    protected void saveSightingInTransaction(NetworkSighting sighting, FingerprintVector candidate, String resolvedHostname) {
        // 2. Look for existing identity
        NetworkIdentity identity = NetworkIdentity.find("select n from NetworkIdentity n join fetch n.physicalDevice where n.ipAddress = ?1 and n.macAddress = ?2", 
                sighting.ipAddress, sighting.macAddress).firstResult();

        if (identity == null) {
            // Check if there is an active identity on this IP first
            NetworkIdentity activeIdOnIp = NetworkIdentity.find("select n from NetworkIdentity n join fetch n.physicalDevice where n.ipAddress = ?1 and n.current = true", sighting.ipAddress).firstResult();
            if (activeIdOnIp != null) {
                boolean sightingIsPlaceholder = sighting.macAddress == null || sighting.macAddress.equals("00:00:00:00:00:00") || sighting.macAddress.isEmpty();
                boolean activeIsPlaceholder = activeIdOnIp.macAddress == null || activeIdOnIp.macAddress.equals("00:00:00:00:00:00") || activeIdOnIp.macAddress.isEmpty();
                boolean derivedMacMatch = isDerivedMacMatch(sighting.macAddress, activeIdOnIp.macAddress);

                // Reuse activeIdOnIp if placeholder OR if sighting MAC is derived from active MAC (e.g. VAP / randomized MAC on OpenWrt/Linux)
                if (sightingIsPlaceholder || activeIsPlaceholder || derivedMacMatch) {
                    identity = activeIdOnIp;
                    if (!sightingIsPlaceholder && activeIsPlaceholder) {
                        // ARP scan upgraded a placeholder MAC to a real MAC — record this in the correlation history.
                        String oldMac = identity.macAddress;
                        identity.macAddress = sighting.macAddress;
                        LOG.info("MAC address resolved for IP " + sighting.ipAddress + ": " + oldMac + " -> " + sighting.macAddress);
                        FingerprintCorrelationEvent macEvent = new FingerprintCorrelationEvent();
                        macEvent.physicalDevice = identity.physicalDevice;
                        macEvent.ipAddress = sighting.ipAddress;
                        macEvent.macAddress = sighting.macAddress;
                        macEvent.hostname = identity.hostname;
                        macEvent.decisionType = "MAC_RESOLVED";
                        macEvent.confidenceScore = 0.9;
                        macEvent.details = "MAC address resolved from ARP scan: " + sighting.macAddress
                                + (isGloballyUniqueMac(sighting.macAddress) ? " (globally unique)" : " (locally administered / randomized)");
                        macEvent.timestamp = sighting.observedAt;
                        macEvent.persist();
                    }
                }
            }

            if (identity == null) {
                boolean isTestMode = io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST;
                boolean isManual = isTestMode || "MANUAL_DISCOVERY".equals(sighting.source);

                boolean hasMetadata = (candidate.hostname != null && !candidate.hostname.isEmpty())
                        || (candidate.dhcpOption55 != null && !candidate.dhcpOption55.isEmpty())
                        || (candidate.dhcpOption60 != null && !candidate.dhcpOption60.isEmpty())
                        || (candidate.openPorts != null && !candidate.openPorts.isEmpty())
                        || (candidate.sshHostKeys != null && !candidate.sshHostKeys.isEmpty());

                boolean isPlaceholderMac = sighting.macAddress == null || sighting.macAddress.isEmpty() || "00:00:00:00:00:00".equals(sighting.macAddress);
                boolean isRandomizedMac = !isPlaceholderMac && !isGloballyUniqueMac(sighting.macAddress);

                // Defer creating NEW physical devices for 0-signal background sightings (placeholder or randomized MAC) until fingerprint metadata arrives.
                // However, a placeholder MAC MUST always be deferred in background scans, because without a MAC we cannot track it across IPs.
                if (!isManual) {
                    if (isPlaceholderMac) {
                        LOG.debug("Deferring new device creation: placeholder MAC on IP " + sighting.ipAddress);
                        return;
                    }
                    if (isRandomizedMac && !hasMetadata) {
                        LOG.debug("Deferring new device creation: 0-signal randomized MAC on IP " + sighting.ipAddress);
                        return;
                    }
                }
            }
        }

        if (identity != null) {
            // Identity exists -> update timestamps
            identity.lastSeen = sighting.observedAt;
            
            // Check if hostname needs to be updated/resolved (Only if NOT MANAGED)
            if (identity.physicalDevice.managementState != com.gnm.model.enums.ManagementState.MANAGED && (identity.hostname == null || identity.hostname.isEmpty())) {
                if (resolvedHostname != null) {
                    identity.hostname = resolvedHostname;
                    if (identity.physicalDevice.displayName.startsWith("Discovered Host ")) {
                        identity.physicalDevice.displayName = resolvedHostname;
                    }
                }
            }

            PhysicalDevice device = identity.physicalDevice;
            
            boolean statusChanged = (device.status != DeviceStatus.ONLINE);
            device.status = DeviceStatus.ONLINE;
            device.lastSeen = sighting.observedAt;
            device.consecutiveMissedProbes = 0; // Reset on any successful sighting
            
            // Ensure only this identity is marked current
            List<NetworkIdentity> allIdentities = NetworkIdentity.list("physicalDevice.id", device.id);
            for (NetworkIdentity oldId : allIdentities) {
                if (oldId.id.equals(identity.id)) {
                    oldId.current = true;
                } else {
                    oldId.current = false;
                }
                oldId.persist();
            }
            
            // Merge matching candidate signals to historical vector
            // Merge matching candidate signals to historical vector
            FingerprintVector historical = FingerprintVector.find("physicalDevice.id = ?1", device.id).firstResult();
            if (historical != null) {
                checkSignatureMutations(candidate, historical, sighting);
                mergeVectors(candidate, historical);
            }
            
            device.persist();
            identity.persist();

            // Enforce IP Uniqueness for this IP
            enforceIpUniqueness(sighting.ipAddress, device.id);

            if (statusChanged) {
                eventBroadcaster.fireAsync(new DeviceEvent("STATUS_CHANGE", device.id.toString(), device.displayName, "ONLINE", sighting.ipAddress));
            }
            return;
        }

        // 3b. Match against existing devices using Similarity Engine
        List<FingerprintVector> allFingerprints = FingerprintVector.list("select distinct f from FingerprintVector f left join fetch f.physicalDevice d left join fetch d.identities");
        PhysicalDevice bestMatch = null;
        double bestScore = 0.0;
        List<String> bestDetails = new ArrayList<>();
        boolean matchedViaExactMac = false;

        for (FingerprintVector hist : allFingerprints) {
            boolean macMismatch = false;
            boolean exactMacMatch = false;
            // Strict Separation Rule: If the candidate MAC is globally unique (permanent hardware),
            // and the historical device has any globally unique MAC that is different, they CANNOT be merged.
            // This ensures two distinct physical devices (e.g. two different laptops) are never conflated.
            // Locally-administered MACs (randomized) are exempt from this rule.
            if (hist.physicalDevice != null && hist.physicalDevice.identities != null) {
                for (NetworkIdentity id : hist.physicalDevice.identities) {
                    if (isDerivedMacMatch(sighting.macAddress, id.macAddress)) {
                        exactMacMatch = true;
                        break;
                    } else if (isGloballyUniqueMac(sighting.macAddress) && isGloballyUniqueMac(id.macAddress)) {
                        if (!sighting.macAddress.equalsIgnoreCase(id.macAddress)) {
                            macMismatch = true;
                            break;
                        }
                    }
                }
            }
            if (macMismatch) {
                continue; // Enforce separate device profile for different globally-unique MACs
            }

            if (exactMacMatch) {
                bestScore = 1.0;
                bestMatch = hist.physicalDevice;
                matchedViaExactMac = true;
                break;
            }

            SimilarityEngine.SimilarityResult result = similarityEngine.calculateSimilarity(candidate, hist);
            if (result.score > bestScore) {
                bestScore = result.score;
                bestDetails = result.details;
                bestMatch = hist.physicalDevice;
            }
        }


        // 4. Evaluate score thresholds
        if (bestScore >= mergeThreshold && bestMatch != null) {
            LOG.info("Matching new identity (" + sighting.ipAddress + " / " + sighting.macAddress + 
                     ") to existing device: " + bestMatch.displayName + " (Confidence: " + Math.round(bestScore*100) + "%)");
            
            // Enforce IP Uniqueness: Deactivate current flag on ANY physical device currently claiming this IP
            enforceIpUniqueness(sighting.ipAddress, bestMatch.id);

            // Auto-merge any existing duplicate PhysicalDevices sharing derived MACs
            mergeDuplicateDevices(bestMatch, sighting.macAddress);

            // Deactivate all previous current flags for this device
            List<NetworkIdentity> oldIdentities = NetworkIdentity.list("physicalDevice.id", bestMatch.id);
            for (NetworkIdentity oldId : oldIdentities) {
                oldId.current = false;
                oldId.persist();
            }

            // Merge Identity
            NetworkIdentity newId = new NetworkIdentity();
            newId.physicalDevice = bestMatch;
            newId.ipAddress = sighting.ipAddress;
            newId.macAddress = sighting.macAddress;
            newId.firstSeen = sighting.observedAt;
            newId.lastSeen = sighting.observedAt;
            newId.current = true;
            newId.hostname = resolvedHostname;
            newId.persist();

            bestMatch.status = DeviceStatus.ONLINE;
            bestMatch.lastSeen = sighting.observedAt;
            bestMatch.consecutiveMissedProbes = 0;
            bestMatch.confidenceScore = (bestMatch.confidenceScore + bestScore) / 2.0; // rolling average
            
            FingerprintVector historical = FingerprintVector.find("physicalDevice.id = ?1", bestMatch.id).firstResult();
            if (historical != null) {
                checkSignatureMutations(candidate, historical, sighting);
                mergeVectors(candidate, historical);
            }
            bestMatch.persist();
            if (candidate.openPorts != null) {
                try {
                    syncNetworkServices(bestMatch, candidate.openPorts);
                } catch (Exception e) {
                    LOG.error("Failed to sync network services for bestMatch " + bestMatch.id, e);
                }
            }

            FingerprintCorrelationEvent correlationEvent = new FingerprintCorrelationEvent();
            correlationEvent.physicalDevice = bestMatch;
            correlationEvent.ipAddress = sighting.ipAddress;
            correlationEvent.macAddress = sighting.macAddress;
            correlationEvent.hostname = candidate.hostname;
            if (matchedViaExactMac) {
                correlationEvent.decisionType = "DIRECT_MATCH";
                correlationEvent.details = "Direct MAC match on " + sighting.macAddress;
            } else {
                correlationEvent.decisionType = "SIMILARITY_MATCH";
                StringBuilder sb = new StringBuilder();
                sb.append("Matched existing device '").append(bestMatch.displayName)
                  .append("' using Similarity Engine (Score: ").append(Math.round(bestScore * 100)).append("%)\n\n");
                sb.append("Calculation Breakdown:\n");
                for (String detail : bestDetails) {
                    sb.append(detail).append("\n");
                }
                correlationEvent.details = sb.toString().trim();
            }
            correlationEvent.confidenceScore = bestScore;
            correlationEvent.timestamp = sighting.observedAt;
            correlationEvent.persist();

            eventBroadcaster.fireAsync(new DeviceEvent("STATUS_CHANGE", bestMatch.id.toString(), bestMatch.displayName, "ONLINE", sighting.ipAddress));
        } else {
            GlobalSetting modeSetting = GlobalSetting.findById("APP_MODE");
            String appMode = modeSetting != null ? modeSetting.value : "DISCOVERY";

            if ("DETECTION".equals(appMode)) {
                LOG.warn("IDS DETECTION MODE: Unknown device detected on network! Generating ThreatEvent.");
                String desc = "Rogue Device Detected: Unauthorized access attempt from " + (resolvedHostname != null ? resolvedHostname : "Unknown");
                ThreatEvent existing = ThreatEvent.find("ipAddress = ?1 and macAddress = ?2 and description = ?3", sighting.ipAddress, sighting.macAddress, desc).firstResult();
                if (existing != null) {
                    if (existing.resolved) {
                        return; // Ignore if user already explicitly resolved this rogue device sighting
                    }
                    existing.detectedAt = Instant.now();
                    existing.persist();
                    threatBroadcaster.fireAsync(existing);
                } else {
                    ThreatEvent threat = new ThreatEvent();
                    threat.severity = "CRITICAL";
                    threat.description = desc;
                    threat.ipAddress = sighting.ipAddress;
                    threat.macAddress = sighting.macAddress;
                    threat.detectedAt = Instant.now();
                    threat.persist();
                    threatBroadcaster.fireAsync(threat);
                }
                return; // Do NOT create device in detection mode!
            }

            // No match -> Create new device
            if (bestMatch != null) {
                LOG.info(String.format("Creating new physical device for sighting (%s / %s). Best match was '%s' but confidence score (%d%%) was below merge threshold (%d%%).",
                        sighting.ipAddress, sighting.macAddress, bestMatch.displayName, Math.round(bestScore * 100), Math.round(mergeThreshold * 100)));
            } else {
                LOG.info(String.format("Creating new physical device for sighting (%s / %s). No existing devices had any matching fingerprint features.",
                        sighting.ipAddress, sighting.macAddress));
            }
            
            // Enforce IP Uniqueness: Deactivate current flag on ANY physical device currently claiming this IP
            enforceIpUniqueness(sighting.ipAddress, null);

            PhysicalDevice newDevice = new PhysicalDevice();
            newDevice.displayName = resolvedHostname != null ? resolvedHostname : "Discovered Host " + sighting.ipAddress;
            newDevice.deviceType = DeviceType.IOT; // Default type
            newDevice.firstSeen = sighting.observedAt;
            newDevice.lastSeen = sighting.observedAt;
            newDevice.status = DeviceStatus.ONLINE;
            newDevice.confidenceScore = 1.0;
            newDevice.persistAndFlush();

            NetworkIdentity newId = new NetworkIdentity();
            newId.physicalDevice = newDevice;
            newId.ipAddress = sighting.ipAddress;
            newId.macAddress = sighting.macAddress;
            newId.firstSeen = sighting.observedAt;
            newId.lastSeen = sighting.observedAt;
            newId.current = true;
            newId.hostname = resolvedHostname;
            newId.persistAndFlush();

            FingerprintVector historical = new FingerprintVector();
            historical.physicalDevice = newDevice;
            historical.dhcpOption55 = candidate.dhcpOption55;
            historical.dhcpOption60 = candidate.dhcpOption60;
            historical.mdnsServices = candidate.mdnsServices;
            historical.openPorts = candidate.openPorts;
            historical.sshHostKeys = candidate.sshHostKeys;
            historical.httpServerHeader = candidate.httpServerHeader;
            historical.tlsJa4 = candidate.tlsJa4;
            historical.tlsCertSubject = candidate.tlsCertSubject;
            historical.ssdpUsn = candidate.ssdpUsn;
            historical.hostname = resolvedHostname; // Persist hostname for later hostname-based merging
            historical.capturedAt = Instant.now();
            historical.persist();

            if (candidate.openPorts != null) {
                try {
                    syncNetworkServices(newDevice, candidate.openPorts);
                } catch (Exception e) {
                    LOG.error("Failed to sync network services for device " + newDevice.id, e);
                }
            }

            FingerprintCorrelationEvent correlationEvent = new FingerprintCorrelationEvent();
            correlationEvent.physicalDevice = newDevice;
            correlationEvent.ipAddress = sighting.ipAddress;
            correlationEvent.macAddress = sighting.macAddress;
            correlationEvent.hostname = resolvedHostname;
            correlationEvent.decisionType = "NEW_DEVICE";
            correlationEvent.confidenceScore = 1.0;
            correlationEvent.details = "Device first discovered on network";
            correlationEvent.timestamp = sighting.observedAt;
            correlationEvent.persist();

            eventBroadcaster.fireAsync(new DeviceEvent("NEW_DEVICE", newDevice.id.toString(), newDevice.displayName, "ONLINE", sighting.ipAddress));
        }
    }

    private boolean isDerivedMacMatch(String mac1, String mac2) {
        if (mac1 == null || mac2 == null) return false;
        String clean1 = mac1.replace(":", "").replace("-", "").toUpperCase();
        String clean2 = mac2.replace(":", "").replace("-", "").toUpperCase();
        if (clean1.length() != 12 || clean2.length() != 12) return false;
        if (clean1.equals("000000000000") || clean2.equals("000000000000")) return false;
        
        // Exact MAC match
        if (clean1.equals(clean2)) return true;
        
        // Check if remaining 5 octets (NIC-specific bytes) match
        if (!clean1.substring(2).equals(clean2.substring(2))) return false;
        
        // Compare first octets clearing the 0x02 (Locally Administered / VAP) bit
        try {
            int octet1 = Integer.parseInt(clean1.substring(0, 2), 16) & ~0x02;
            int octet2 = Integer.parseInt(clean2.substring(0, 2), 16) & ~0x02;
            return octet1 == octet2;
        } catch (Exception e) {
            return false;
        }
    }

    private void mergeDuplicateDevices(PhysicalDevice targetDevice, String macAddress) {
        List<NetworkIdentity> allIdentities = NetworkIdentity.listAll();
        for (NetworkIdentity id : allIdentities) {
            if (id.physicalDevice != null && !id.physicalDevice.id.equals(targetDevice.id)) {
                if (isDerivedMacMatch(macAddress, id.macAddress)) {
                    PhysicalDevice duplicate = id.physicalDevice;
                    LOG.info("Auto-merging duplicate PhysicalDevice (" + duplicate.id + " / " + duplicate.displayName + ") into target device (" + targetDevice.id + " / " + targetDevice.displayName + ")");
                    
                    id.physicalDevice = targetDevice;
                    id.current = false;
                    id.persist();

                    List<FingerprintVector> fps = FingerprintVector.list("physicalDevice.id", duplicate.id);
                    for (FingerprintVector fp : fps) {
                        fp.delete();
                    }

                    long remaining = NetworkIdentity.count("physicalDevice.id", duplicate.id);
                    if (remaining == 0) {
                        duplicate.delete();
                    }
                }
            }
        }
    }

    private void enforceIpUniqueness(String ipAddress, java.util.UUID exemptDeviceId) {
        List<NetworkIdentity> activeIdentitiesOnIp = NetworkIdentity.find("select n from NetworkIdentity n join fetch n.physicalDevice where n.ipAddress = ?1 and n.current = true", ipAddress).list();
        for (NetworkIdentity oldId : activeIdentitiesOnIp) {
            if (exemptDeviceId != null && exemptDeviceId.equals(oldId.physicalDevice.id)) {
                continue;
            }
            oldId.current = false;
            oldId.persist();
            
            // Panache will auto-flush before the count query if necessary

            long activeCount = NetworkIdentity.count("physicalDevice.id = ?1 and current = true", oldId.physicalDevice.id);
            if (activeCount == 0) {
                oldId.physicalDevice.status = DeviceStatus.OFFLINE;
                oldId.physicalDevice.persist();
            }
        }
    }

    private boolean isGloballyUniqueMac(String macAddress) {
        if (macAddress == null || macAddress.length() < 2) {
            return false;
        }
        try {
            String cleanMac = macAddress.replace(":", "").replace("-", "");
            if (cleanMac.equals("000000000000") || cleanMac.isEmpty()) {
                return false; // All-zeros or empty is an invalid placeholder, not globally unique
            }
            if (cleanMac.length() < 2) {
                return false;
            }
            String firstOctetHex = cleanMac.substring(0, 2);
            int firstOctet = Integer.parseInt(firstOctetHex, 16);
            // 0x02 is the locally administered bit. If 0, it is globally unique.
            return (firstOctet & 0x02) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public void mergeMetadataByMacInTransaction(String macAddress, FingerprintVector candidate) {
        NetworkIdentity identity = NetworkIdentity.find("select n from NetworkIdentity n join fetch n.physicalDevice where n.macAddress = ?1 and n.current = true", macAddress).firstResult();
        if (identity != null && identity.physicalDevice != null) {
            FingerprintVector historical = FingerprintVector.find("physicalDevice.id = ?1", identity.physicalDevice.id).firstResult();
            if (historical != null) {
                mergeVectors(candidate, historical);
            }
        }
    }
}
