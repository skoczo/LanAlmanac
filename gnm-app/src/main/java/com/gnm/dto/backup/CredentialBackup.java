package com.gnm.dto.backup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.gnm.model.enums.CredentialType;

public class CredentialBackup {
    public UUID id;
    public String label;
    public CredentialType credentialType;
    public byte[] encryptedPayload;
    public byte[] noncePayload;
    public String username;
    public Integer port;
    public Instant createdAt;
    public Instant updatedAt;
}
