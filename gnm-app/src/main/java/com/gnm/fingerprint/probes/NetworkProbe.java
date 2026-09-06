package com.gnm.fingerprint.probes;

/**
 * Interface representing an active or passive network discovery probe.
 * <p>
 * Probes execute sequentially or concurrently based on priority (lower numbers run first)
 * to harvest device fingerprinting metadata (e.g., hostnames, open ports, SSL certificates, UPnP USNs, SSH keys)
 * into a shared {@link ProbeContext}.
 */
public interface NetworkProbe {
    /**
     * Executes the network probe. Modifies the context with gathered data or resolved hostname.
     * 
     * @param context The shared probe context containing target IP address and candidate fingerprint.
     * @throws Exception If an error or timeout occurs during probe execution.
     */
    void execute(ProbeContext context) throws Exception;
    
    /**
     * Returns the maximum time this probe is allowed to execute in milliseconds.
     * 
     * @return timeout duration in milliseconds.
     */
    int getTimeoutMs();
    
    /**
     * Execution order priority. Lower numbers run first in the pipeline.
     * 
     * @return priority integer value.
     */
    int getPriority();

    /**
     * Indicates whether this probe's primary function is resolving device hostnames.
     * Probes returning true will be skipped if a hostname has already been resolved.
     * 
     * @return true if probe resolves hostnames, false if it gathers general fingerprint metadata.
     */
    default boolean isHostnameProbe() {
        return true;
    }
}



