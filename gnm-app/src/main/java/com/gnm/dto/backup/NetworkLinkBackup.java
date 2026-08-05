package com.gnm.dto.backup;

import java.time.Instant;
import java.util.UUID;
import com.gnm.model.enums.DiscoveryProtocol;

public class NetworkLinkBackup {
    public UUID id;
    public UUID sourceDeviceId;
    public UUID targetDeviceId;
    public String sourceInterface;
    public String targetInterface;
    public DiscoveryProtocol discoveryProtocol;
    public Instant lastVerified;
}
