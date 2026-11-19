package com.gnm.pkgmgmt.patching;

import com.gnm.model.PhysicalDevice;
import com.gnm.pkgmgmt.profile.OsProfile;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "patch_job")
public class PatchJob extends PanacheEntityBase {

    @Id
    @Column(name = "id")
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

    @Column(name = "triggered_by", nullable = false, length = 128)
    public String triggeredBy;

    @Column(name = "mode", nullable = false, length = 32)
    public String mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requested_packages", nullable = false, columnDefinition = "jsonb")
    public List<String> requestedPackages = List.of();

    @Column(name = "status", nullable = false, length = 32)
    public String status = "PENDING";

    @Column(name = "current_phase", length = 32)
    public String currentPhase;

    @Column(name = "reboot_required")
    public Boolean rebootRequired;

    @Column(name = "exit_code")
    public Integer exitCode;

    @Column(name = "error_code", length = 64)
    public String errorCode;

    @Column(name = "error_summary")
    public String errorSummary;

    @Column(name = "started_at")
    public Instant startedAt;

    @Column(name = "finished_at")
    public Instant finishedAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}

