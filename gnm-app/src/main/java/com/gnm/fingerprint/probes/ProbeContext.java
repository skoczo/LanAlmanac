package com.gnm.fingerprint.probes;

import com.gnm.model.FingerprintVector;
import java.util.ArrayList;
import java.util.List;

public class ProbeContext {
    private final String ipAddress;
    private final FingerprintVector candidate;
    private List<Integer> openPorts = new ArrayList<>();
    private String resolvedHostname = null;

    public ProbeContext(String ipAddress, FingerprintVector candidate) {
        this.ipAddress = ipAddress;
        this.candidate = candidate;
    }

    public String getIpAddress() { return ipAddress; }
    public FingerprintVector getCandidate() { return candidate; }
    
    public List<Integer> getOpenPorts() { return openPorts; }
    public void setOpenPorts(List<Integer> openPorts) { this.openPorts = openPorts; }

    public String getResolvedHostname() { return resolvedHostname; }
    public void setResolvedHostname(String resolvedHostname) { this.resolvedHostname = resolvedHostname; }
}
