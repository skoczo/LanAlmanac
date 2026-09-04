package com.gnm.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import com.gnm.model.enums.DeviceStatus;

@Entity
@Table(name = "device_status_history")
public class DeviceStatusHistory extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "physical_device_id", nullable = false)
    @JsonIgnore // Prevent infinite recursion in JSON
    public PhysicalDevice physicalDevice;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    public DeviceStatus status;

    @Column(name = "ip_address")
    public String ipAddress;

    @Column(name = "timestamp", nullable = false)
    public Instant timestamp;
}
