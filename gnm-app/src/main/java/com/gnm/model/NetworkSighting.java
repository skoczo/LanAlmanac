package com.gnm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "network_sighting")
public class NetworkSighting extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "network_identity_id", nullable = false)
    public NetworkIdentity networkIdentity;

    @Column(name = "ip_address", nullable = false)
    public String ipAddress;

    @Column(name = "mac_address", nullable = false)
    public String macAddress;

    @Column(name = "source", nullable = false)
    public String source;

    @Column(name = "raw_metadata")
    public String rawMetadata;

    @Column(name = "observed_at", nullable = false)
    public Instant observedAt;
}
