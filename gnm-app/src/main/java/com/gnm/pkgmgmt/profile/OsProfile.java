package com.gnm.pkgmgmt.profile;

import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.InventorySpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.LockSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PatchExecSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.PrivilegeSpec;
import com.gnm.pkgmgmt.client.dto.WorkerContractDtos.UpdateCheckSpec;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.DetectionRules;
import com.gnm.pkgmgmt.profile.OsProfileSpecs.VulnerabilitySpec;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "os_profile")
public class OsProfile extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "slug", nullable = false, unique = true, length = 64)
    public String slug;

    @Column(name = "display_name", nullable = false, length = 128)
    public String displayName;

    @Column(name = "transport_type", nullable = false, length = 32)
    public String transportType = "SSH";

    @Column(name = "priority", nullable = false)
    public int priority = 100;

    @Column(name = "enabled", nullable = false)
    public boolean enabled = true;

    @Column(name = "is_system", nullable = false)
    public boolean isSystem = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detection_rules", nullable = false, columnDefinition = "jsonb")
    public DetectionRules detectionRules;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "environment_vars", nullable = false, columnDefinition = "jsonb")
    public Map<String, String> environmentVars = Map.of("LC_ALL", "C", "LANG", "C");

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inventory_spec", nullable = false, columnDefinition = "jsonb")
    public InventorySpec inventorySpec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "update_check_spec", nullable = false, columnDefinition = "jsonb")
    public UpdateCheckSpec updateCheckSpec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "patch_exec_spec", nullable = false, columnDefinition = "jsonb")
    public PatchExecSpec patchExecSpec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vulnerability_spec", nullable = false, columnDefinition = "jsonb")
    public VulnerabilitySpec vulnerabilitySpec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "privilege_spec", nullable = false, columnDefinition = "jsonb")
    public PrivilegeSpec privilegeSpec;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lock_spec", nullable = false, columnDefinition = "jsonb")
    public LockSpec lockSpec;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}

