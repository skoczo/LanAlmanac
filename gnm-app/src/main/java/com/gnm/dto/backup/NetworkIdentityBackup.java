package com.gnm.dto.backup;

import java.time.Instant;
import java.util.UUID;

public class NetworkIdentityBackup {
    public UUID id;
    public String ipAddress;
    public String macAddress;
    public String hostname;
    public String dhcpLeaseId;
    public Instant firstSeen;
    public Instant lastSeen;
    public boolean current;
}
