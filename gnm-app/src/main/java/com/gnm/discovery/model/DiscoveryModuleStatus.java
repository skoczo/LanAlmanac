package com.gnm.discovery.model;

import java.time.Instant;

public class DiscoveryModuleStatus {

    public String id;
    public String name;
    public Status status;
    public String errorMessage;
    public String currentActivity;
    public Instant lastScanAt;
    public String lastDiscoveredSummary;
    public boolean enabled;

    public enum Status {
        RUNNING,
        STOPPED,
        ERROR,
        DISABLED
    }

    public DiscoveryModuleStatus() {
    }

    public DiscoveryModuleStatus(String id, String name, Status status, String currentActivity, boolean enabled) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.currentActivity = currentActivity;
        this.enabled = enabled;
        this.lastScanAt = Instant.now();
    }
}
