package com.gnm.resource;

import com.gnm.model.Credential;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.CredentialType;
import com.gnm.service.VaultEngine;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

@Path("/api/credentials")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CredentialResource {

    private static final String KEY_ERROR = "error";
    private static final String MSG_VAULT_SEALED = "Vault is sealed";
    private static final String KEY_SECRET = "secret";
    private static final String KEY_LABEL = "label";
    private static final String KEY_TYPE = "type";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PORT = "port";

    private final VaultEngine vaultEngine;

    @GET
    @Path("/device/{deviceId}")
    public Response getCredentials(@PathParam("deviceId") UUID deviceId) {
        List<Credential> creds = Credential.find("physicalDevice.id", deviceId).list();
        List<Map<String, Object>> response = creds.stream().map(c -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.id);
            map.put(KEY_LABEL, c.label);
            map.put(KEY_TYPE, c.credentialType);
            map.put(KEY_USERNAME, c.username == null ? "" : c.username);
            map.put(KEY_PORT, c.port == null ? "" : c.port);
            map.put("createdAt", c.createdAt);
            return map;
        }).toList();
        return Response.ok(response).build();
    }

    @POST
    @Path("/device/{deviceId}")
    @Transactional
    public Response addCredential(@PathParam("deviceId") UUID deviceId, Map<String, Object> payload) {
        if (!vaultEngine.isUnsealed()) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of(KEY_ERROR, MSG_VAULT_SEALED)).build();
        }
        
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        Credential cred = new Credential();
        cred.physicalDevice = device;
        cred.label = (String) payload.get(KEY_LABEL);
        cred.credentialType = CredentialType.valueOf((String) payload.get(KEY_TYPE));
        cred.username = (String) payload.get(KEY_USERNAME);
        if (payload.get(KEY_PORT) != null && !payload.get(KEY_PORT).toString().isBlank()) {
            cred.port = Integer.parseInt(payload.get(KEY_PORT).toString());
        }
        
        String secret = (String) payload.get(KEY_SECRET);
        if (secret != null && !secret.isEmpty()) {
            VaultEngine.EncryptedRecord encrypted = vaultEngine.encrypt(secret.getBytes(StandardCharsets.UTF_8));
            cred.encryptedPayload = encrypted.ciphertext;
            cred.noncePayload = encrypted.iv;
        } else {
            cred.encryptedPayload = new byte[0];
            cred.noncePayload = new byte[0];
        }
        
        cred.createdAt = Instant.now();
        cred.updatedAt = Instant.now();
        cred.persist();
        
        return Response.ok(Map.of("id", cred.id)).build();
    }

    @GET
    @Path("/{id}/reveal")
    public Response revealCredential(@PathParam("id") UUID id) {
        if (!vaultEngine.isUnsealed()) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of(KEY_ERROR, MSG_VAULT_SEALED)).build();
        }
        
        Credential cred = Credential.findById(id);
        if (cred == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        if (cred.encryptedPayload == null || cred.encryptedPayload.length == 0) {
             return Response.ok(Map.of(KEY_SECRET, "")).build();
        }
        
        try {
            byte[] plaintext = vaultEngine.decrypt(cred.encryptedPayload, cred.noncePayload);
            String secretStr = new String(plaintext, StandardCharsets.UTF_8);
            return Response.ok(Map.of(KEY_SECRET, secretStr)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of(KEY_ERROR, "Decryption failed")).build();
        }
    }
    
    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteCredential(@PathParam("id") UUID id) {
        Credential cred = Credential.findById(id);
        if (cred != null) {
            cred.delete();
        }
        return Response.noContent().build();
    }
    
    @PUT
    @Path("/{id}")
    @Transactional
    public Response updateCredential(@PathParam("id") UUID id, Map<String, Object> payload) {
        if (!vaultEngine.isUnsealed()) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of(KEY_ERROR, MSG_VAULT_SEALED)).build();
        }
        
        Credential cred = Credential.findById(id);
        if (cred == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        if (payload.containsKey(KEY_LABEL)) cred.label = (String) payload.get(KEY_LABEL);
        if (payload.containsKey(KEY_TYPE)) cred.credentialType = CredentialType.valueOf((String) payload.get(KEY_TYPE));
        if (payload.containsKey(KEY_USERNAME)) cred.username = (String) payload.get(KEY_USERNAME);
        
        if (payload.containsKey(KEY_PORT)) {
            Object portObj = payload.get(KEY_PORT);
            if (portObj != null && !portObj.toString().isBlank()) {
                cred.port = Integer.parseInt(portObj.toString());
            } else {
                cred.port = null;
            }
        }
        
        if (payload.containsKey(KEY_SECRET)) {
            String secret = (String) payload.get(KEY_SECRET);
            if (secret != null && !secret.isEmpty()) {
                VaultEngine.EncryptedRecord encrypted = vaultEngine.encrypt(secret.getBytes(StandardCharsets.UTF_8));
                cred.encryptedPayload = encrypted.ciphertext;
                cred.noncePayload = encrypted.iv;
            }
        }
        
        cred.updatedAt = Instant.now();
        cred.persist();
        
        return Response.ok(Map.of("id", cred.id)).build();
    }

    @Inject
    public CredentialResource(VaultEngine vaultEngine) {
        this.vaultEngine = vaultEngine;
    }
}
