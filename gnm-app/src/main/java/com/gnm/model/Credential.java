package com.gnm.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.gnm.model.enums.CredentialType;

@Entity
@Table(name = "credential")
public class Credential extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "physical_device_id", nullable = false)
    @JsonIgnore
    public PhysicalDevice physicalDevice;

    @Column(name = "label", nullable = false)
    public String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false)
    public CredentialType credentialType;

    @Column(name = "encrypted_payload", nullable = false)
    public byte[] encryptedPayload;

    @Column(name = "nonce_payload", nullable = false)
    public byte[] noncePayload;

    @Column(name = "username")
    public String username;

    @Column(name = "port")
    public Integer port;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
