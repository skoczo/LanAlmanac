package com.gnm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

@Entity
@Table(name = "network_service")
public class NetworkService extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "physical_device_id", nullable = false)
    @JsonIgnore
    public PhysicalDevice physicalDevice;

    @Column(name = "label")
    public String label;

    @Column(name = "service_type", nullable = false)
    public String serviceType;

    @Column(name = "protocol")
    public String protocol;

    @Column(name = "port", nullable = false)
    public Integer port;

    @Column(name = "manageable")
    public Boolean manageable;

    @Column(name = "discovered")
    public Boolean discovered;

    @Column(name = "first_seen", nullable = false)
    public Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    public Instant lastSeen;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "credential_id")
    public Credential credential;
}
