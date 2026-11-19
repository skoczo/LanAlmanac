package com.gnm.pkgmgmt.inventory;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.profile.OsProfile;
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
@Table(name = "device_package_scan")
public class DevicePackageScan extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "physical_device_id", nullable = false)
    public PhysicalDevice physicalDevice;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "os_profile_id")
    public OsProfile osProfile;

    @Column(name = "credential_id")
    public UUID credentialId;

    @Column(name = "credential_username", length = 128)
    public String credentialUsername;

    @Column(name = "used_fallback", nullable = false)
    public boolean usedFallback = false;

    @Column(name = "status", nullable = false, length = 32)
    public String status = "RUNNING";

    @Column(name = "package_count", nullable = false)
    public int packageCount = 0;

    @Column(name = "skipped_lines", nullable = false)
    public int skippedLines = 0;

    @Column(name = "error_code", length = 64)
    public String errorCode;

    @Column(name = "error_message")
    public String errorMessage;

    @Column(name = "started_at", nullable = false)
    public Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    public Instant finishedAt;
}

