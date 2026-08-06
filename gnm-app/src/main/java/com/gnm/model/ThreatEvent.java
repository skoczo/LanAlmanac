package com.gnm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "threat_event")
public class ThreatEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "severity")
    public String severity; // e.g. HIGH, MEDIUM, LOW

    @Column(name = "description")
    public String description;

    @Column(name = "physical_device_id")
    public UUID physicalDeviceId;

    @Column(name = "ip_address")
    public String ipAddress;

    @Column(name = "mac_address")
    public String macAddress;

    @Column(name = "detected_at")
    public Instant detectedAt;

    @Column(name = "resolved")
    public boolean resolved = false;

    @Column(name = "notes", columnDefinition = "TEXT")
    public String notes;
}
