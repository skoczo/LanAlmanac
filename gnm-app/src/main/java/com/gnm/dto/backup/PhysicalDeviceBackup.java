package com.gnm.dto.backup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.DeviceType;
import com.gnm.model.enums.ManagementState;

public class PhysicalDeviceBackup {
    public UUID id;
    public String displayName;
    public DeviceType deviceType;
    public String osFamily;
    public String osVersion;
    public String manufacturer;
    public String model;
    public String locationNote;
    public Double confidenceScore;
    public Boolean manuallyVerified;
    public Instant firstSeen;
    public Instant lastSeen;
    public DeviceStatus status;
    public ManagementState managementState;
    public Set<String> labels = new HashSet<>();
    public List<NetworkIdentityBackup> identities = new ArrayList<>();
    public List<FingerprintVectorBackup> fingerprints = new ArrayList<>();
    public List<CredentialBackup> credentials = new ArrayList<>();
}
