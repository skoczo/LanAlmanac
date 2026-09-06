package com.gnm.fingerprint.probes;

import com.gnm.model.FingerprintVector;
import java.util.ArrayList;
import java.util.List;

/**
 * Encapsulates the runtime context and accumulated results for a single probe execution sequence.
 * <p>
 * Holds target IP address, active fingerprint candidate vector, discovered open ports,
 * and the first successfully resolved hostname.
 */
public class ProbeContext {
    private final String ipAddress;
    private final FingerprintVector candidate;
    private List<Integer> openPorts = new ArrayList<>();
    private String resolvedHostname = null;

    /**
     * Constructs a ProbeContext for probing a specific network IP address.
     * 
     * @param ipAddress Target IP address to probe.
     * @param candidate Candidate fingerprint vector to populate with probe observations.
     */
    public ProbeContext(String ipAddress, FingerprintVector candidate) {
        this.ipAddress = ipAddress;
        this.candidate = candidate;
    }

    /**
     * Returns the target IP address being probed.
     */
    public String getIpAddress() { return ipAddress; }

    /**
     * Returns the mutable fingerprint candidate vector associated with this target device.
     */
    public FingerprintVector getCandidate() { return candidate; }
    
    /**
     * Returns the list of open TCP/UDP ports discovered on the target.
     */
    public List<Integer> getOpenPorts() { return openPorts; }

    /**
     * Sets the list of open ports discovered on the target.
     */
    public void setOpenPorts(List<Integer> openPorts) { this.openPorts = openPorts; }

    /**
     * Returns the resolved hostname, or null if no probe has successfully resolved it yet.
     */
    public String getResolvedHostname() { return resolvedHostname; }

    /**
     * Sets the resolved hostname for the target device.
     */
    public void setResolvedHostname(String resolvedHostname) { this.resolvedHostname = resolvedHostname; }
}

