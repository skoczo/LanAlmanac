package com.gnm.resource;

import com.gnm.service.VaultEngine;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.annotation.security.PermitAll;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/vault")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
public class VaultResource {

    @Inject
    VaultEngine vaultEngine;

    @ConfigProperty(name = "gnm.vault.password")
    Optional<String> vaultPassword;

    public static class PasswordPayload {
        public String password;
    }


    @GET
    @Path("/status")
    public Response getStatus() {
        boolean autoUnsealEnabled = System.getenv("GNM_VAULT_PASSWORD") != null
                && !System.getenv("GNM_VAULT_PASSWORD").trim().isEmpty();
        return Response.ok(Map.of(
                "initialized", vaultEngine.isInitialized(),
                "sealed", !vaultEngine.isUnsealed(),
                "autoUnsealEnabled", autoUnsealEnabled
        )).build();
    }

    @POST
    @Path("/init")
    public Response initialize(PasswordPayload payload) {
        String passcode = (payload != null && payload.password != null && !payload.password.trim().isEmpty()) 
                ? payload.password 
                : vaultPassword.orElse(null);

        if (passcode == null || passcode.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Vault password not provided and not configured on server")).build();
        }
        if (vaultEngine.isInitialized()) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", "Vault already initialized")).build();
        }
        vaultEngine.initializeVault(passcode);
        return Response.ok(Map.of("success", true)).build();
    }

    @POST
    @Path("/unseal")
    public Response unseal(PasswordPayload payload) {
        String passcode = (payload != null && payload.password != null && !payload.password.trim().isEmpty()) 
                ? payload.password 
                : vaultPassword.orElse(null);

        if (passcode == null || passcode.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Vault password not provided and not configured on server")).build();
        }
        if (!vaultEngine.isInitialized()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Vault not initialized")).build();
        }
        boolean success = vaultEngine.unsealVault(passcode);
        if (success) {
            return Response.ok(Map.of("success", true)).build();
        } else {
            return Response.status(Response.Status.UNAUTHORIZED).entity(Map.of("error", "Invalid server passcode")).build();
        }
    }
    
    @POST
    @Path("/lock")
    public Response lock() {
        vaultEngine.lockVault();
        return Response.ok(Map.of("success", true)).build();
    }
}
