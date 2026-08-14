package com.gnm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "fingerprint_correlation_event")
public class FingerprintCorrelationEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "physical_device_id", nullable = false)
    @JsonIgnore
    public PhysicalDevice physicalDevice;

    @Column(name = "ip_address", nullable = false)
    public String ipAddress;

    @Column(name = "mac_address", nullable = false)
    public String macAddress;

    @Column(name = "hostname")
    public String hostname;

    @Column(name = "decision_type", nullable = false)
    public String decisionType; // "NEW_DEVICE", "DIRECT_MATCH", "HOSTNAME_MATCH", "SIMILARITY_MATCH"

    @Column(name = "confidence_score", nullable = false)
    public Double confidenceScore;

    @Column(name = "details")
    public String details;

    @Column(name = "timestamp", nullable = false)
    public Instant timestamp;
}
