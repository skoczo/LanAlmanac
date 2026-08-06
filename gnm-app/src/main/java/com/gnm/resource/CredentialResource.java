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
import java.util.stream.Collectors;
import java.util.HashMap;

@Path("/api/credentials")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CredentialResource {

    @Inject
    VaultEngine vaultEngine;

    @GET
    @Path("/device/{deviceId}")
    public Response getCredentials(@PathParam("deviceId") UUID deviceId) {
        List<Credential> creds = Credential.find("physicalDevice.id", deviceId).list();
        List<Map<String, Object>> response = creds.stream().map(c -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.id);
            map.put("label", c.label);
            map.put("type", c.credentialType);
            map.put("username", c.username == null ? "" : c.username);
            map.put("port", c.port == null ? "" : c.port);
            map.put("createdAt", c.createdAt);
            return map;
        }).collect(Collectors.toList());
        return Response.ok(response).build();
    }

    @POST
    @Path("/device/{deviceId}")
    @Transactional
    public Response addCredential(@PathParam("deviceId") UUID deviceId, Map<String, Object> payload) {
        if (!vaultEngine.isUnsealed()) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Vault is sealed")).build();
        }
        
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        Credential cred = new Credential();
        cred.physicalDevice = device;
        cred.label = (String) payload.get("label");
        cred.credentialType = CredentialType.valueOf((String) payload.get("type"));
        cred.username = (String) payload.get("username");
        if (payload.get("port") != null && !payload.get("port").toString().isBlank()) {
            cred.port = Integer.parseInt(payload.get("port").toString());
        }
        
        String secret = (String) payload.get("secret");
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
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Vault is sealed")).build();
        }
        
        Credential cred = Credential.findById(id);
        if (cred == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        if (cred.encryptedPayload == null || cred.encryptedPayload.length == 0) {
             return Response.ok(Map.of("secret", "")).build();
        }
        
        try {
            byte[] plaintext = vaultEngine.decrypt(cred.encryptedPayload, cred.noncePayload);
            String secret = new String(plaintext, StandardCharsets.UTF_8);
            return Response.ok(Map.of("secret", secret)).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", "Decryption failed")).build();
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
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", "Vault is sealed")).build();
        }
        
        Credential cred = Credential.findById(id);
        if (cred == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        if (payload.containsKey("label")) cred.label = (String) payload.get("label");
        if (payload.containsKey("type")) cred.credentialType = CredentialType.valueOf((String) payload.get("type"));
        if (payload.containsKey("username")) cred.username = (String) payload.get("username");
        
        if (payload.containsKey("port")) {
            Object portObj = payload.get("port");
            if (portObj != null && !portObj.toString().isBlank()) {
                cred.port = Integer.parseInt(portObj.toString());
            } else {
                cred.port = null;
            }
        }
        
        if (payload.containsKey("secret")) {
            String secret = (String) payload.get("secret");
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
}
