package com.gnm.pkgmgmt.updates;

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
@Table(name = "device_available_update")
public class DeviceAvailableUpdate extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "physical_device_id", nullable = false)
    public PhysicalDevice physicalDevice;

    @Column(name = "package_name", nullable = false, length = 255)
    public String packageName;

    @Column(name = "arch", nullable = false, length = 64)
    public String arch = "";

    @Column(name = "current_version", length = 128)
    public String currentVersion;

    @Column(name = "candidate_version", nullable = false, length = 128)
    public String candidateVersion;

    @Column(name = "is_security_update", nullable = false)
    public boolean isSecurityUpdate = false;

    @Column(name = "checked_at", nullable = false)
    public Instant checkedAt = Instant.now();
}

