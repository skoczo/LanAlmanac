package com.gnm.pkgmgmt.inventory;

import com.gnm.model.PhysicalDevice;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_package")
public class DevicePackage extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "physical_device_id", nullable = false)
    public PhysicalDevice physicalDevice;

    @Column(name = "purl", nullable = false, length = 512)
    public String purl;

    @Column(name = "ecosystem", nullable = false, length = 64)
    public String ecosystem;

    @Column(name = "name", nullable = false, length = 255)
    public String name;

    @Column(name = "version", nullable = false, length = 128)
    public String version;

    @Column(name = "arch", nullable = false, length = 64)
    public String arch = "";

    @Column(name = "source_name", nullable = false, length = 255)
    public String sourceName;

    @Column(name = "source_version", nullable = false, length = 128)
    public String sourceVersion;

    @Column(name = "last_scan_id")
    public UUID lastScanId;

    @Column(name = "last_seen_at", nullable = false)
    public Instant lastSeenAt = Instant.now();
}

