package com.gnm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.DeviceType;

@Entity
@Table(name = "physical_device")
public class PhysicalDevice extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "display_name", nullable = false)
    public String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type")
    public DeviceType deviceType;

    @Column(name = "os_family")
    public String osFamily;

    @Column(name = "os_version")
    public String osVersion;

    @Column(name = "manufacturer")
    public String manufacturer;

    @Column(name = "model")
    public String model;

    @Column(name = "location_note")
    public String locationNote;

    @Column(name = "confidence_score")
    public Double confidenceScore = 1.0;

    @Column(name = "manually_verified")
    public Boolean manuallyVerified = false;

    @Column(name = "first_seen", nullable = false)
    public Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    public Instant lastSeen;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    public DeviceStatus status = DeviceStatus.UNKNOWN;

    @OneToMany(mappedBy = "physicalDevice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<NetworkIdentity> identities = new ArrayList<>();

    @OneToMany(mappedBy = "physicalDevice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<FingerprintVector> fingerprints = new ArrayList<>();

    @OneToMany(mappedBy = "physicalDevice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    public List<Credential> credentials = new ArrayList<>();
}
