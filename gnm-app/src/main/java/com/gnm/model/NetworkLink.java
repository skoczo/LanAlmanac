package com.gnm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.GenericGenerator;
import java.time.Instant;
import java.util.UUID;
import com.gnm.model.enums.DiscoveryProtocol;

@Entity
@Table(name = "network_link")
public class NetworkLink extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_device_id", nullable = false)
    public PhysicalDevice sourceDevice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_device_id", nullable = false)
    public PhysicalDevice targetDevice;

    @Column(name = "source_interface")
    public String sourceInterface;

    @Column(name = "target_interface")
    public String targetInterface;

    @Enumerated(EnumType.STRING)
    @Column(name = "discovery_protocol", nullable = false)
    public DiscoveryProtocol discoveryProtocol = DiscoveryProtocol.LLDP;

    @Column(name = "last_verified", nullable = false)
    public Instant lastVerified;
}
