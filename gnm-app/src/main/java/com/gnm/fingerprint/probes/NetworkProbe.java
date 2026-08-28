package com.gnm.fingerprint.probes;

public interface NetworkProbe {
    /**
     * Executes the network probe. Modifies the context with gathered data.
     * @param context The shared probe context.
     */
    void execute(ProbeContext context);
    
    /**
     * Returns the maximum time this probe can take in milliseconds.
     */
    int getTimeoutMs();
    
    /**
     * Execution order priority. Lower numbers run first.
     */
    int getPriority();
}
