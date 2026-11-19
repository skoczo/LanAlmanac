package com.gnm.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.gnm.model.enums.CredentialCategory;
import com.gnm.model.enums.CredentialType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "credential_category",
            joinColumns = @JoinColumn(name = "credential_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    public Set<CredentialCategory> categories = new HashSet<>();
}
