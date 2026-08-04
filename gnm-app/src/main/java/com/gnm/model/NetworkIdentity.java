package com.gnm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "network_identity")
public class NetworkIdentity extends PanacheEntityBase {

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

    @Column(name = "dhcp_lease_id")
    public String dhcpLeaseId;

    @Column(name = "first_seen", nullable = false)
    public Instant firstSeen;

    @Column(name = "last_seen", nullable = false)
    public Instant lastSeen;

    @Column(name = "current")
    public Boolean current = true;

    @OneToMany(mappedBy = "networkIdentity", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JsonIgnore
    public List<NetworkSighting> sightings = new ArrayList<>();
}
