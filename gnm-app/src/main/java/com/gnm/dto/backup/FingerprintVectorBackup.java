package com.gnm.dto.backup;

import java.time.Instant;
import java.util.UUID;

public class FingerprintVectorBackup {
    public UUID id;
    public int version;
    public String dhcpOption55;
    public String dhcpOption60;
    public String tcpFingerprint;
    public String mdnsServices;
    public String ssdpUsn;
    public String sshBanner;
    public String httpServerHeader;
    public String tlsJa4;
    public String tlsCertSubject;
    public String openPorts;
    public String macOui;
    public Instant capturedAt;
}
