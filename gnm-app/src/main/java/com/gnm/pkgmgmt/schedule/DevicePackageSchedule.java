package com.gnm.pkgmgmt.schedule;

import com.gnm.model.PhysicalDevice;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_package_schedule")
public class DevicePackageSchedule extends PanacheEntityBase {

    @Id
    @Column(name = "device_id")
    public UUID deviceId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "device_id", nullable = false)
    public PhysicalDevice device;

    @Column(name = "inventory_enabled", nullable = false)
    public boolean inventoryEnabled = false;

    @Column(name = "inventory_cron", nullable = false, length = 64)
    public String inventoryCron = "0 3 * * *";

    @Column(name = "next_inventory_run_at")
    public Instant nextInventoryRunAt;

    @Column(name = "last_inventory_run_at")
    public Instant lastInventoryRunAt;

    @Column(name = "update_check_enabled", nullable = false)
    public boolean updateCheckEnabled = false;

    @Column(name = "update_check_cron", nullable = false, length = 64)
    public String updateCheckCron = "0 4 * * *";

    @Column(name = "next_update_check_run_at")
    public Instant nextUpdateCheckRunAt;

    @Column(name = "last_update_check_run_at")
    public Instant lastUpdateCheckRunAt;

    @Column(name = "auto_patch_enabled", nullable = false)
    public boolean autoPatchEnabled = false;

    @Column(name = "auto_patch_mode", nullable = false, length = 32)
    public String autoPatchMode = "SECURITY_ONLY";

    @Column(name = "auto_patch_cron", nullable = false, length = 64)
    public String autoPatchCron = "0 5 * * 0";

    @Column(name = "next_auto_patch_run_at")
    public Instant nextAutoPatchRunAt;

    @Column(name = "last_auto_patch_run_at")
    public Instant lastAutoPatchRunAt;

    @Column(name = "timezone", nullable = false, length = 64)
    public String timezone = "UTC";

    @Column(name = "updated_by", length = 128)
    public String updatedBy;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
