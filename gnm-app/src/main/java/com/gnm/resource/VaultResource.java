package com.gnm.resource;

import com.gnm.service.VaultEngine;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.annotation.security.PermitAll;
import java.util.Map;

@Path("/api/vault")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@PermitAll
public class VaultResource {

    @Inject
    VaultEngine vaultEngine;

    @GET
    @Path("/status")
    public Response getStatus() {
        return Response.ok(Map.of(
                "initialized", vaultEngine.isInitialized(),
                "sealed", !vaultEngine.isUnsealed()
        )).build();
    }

    @POST
    @Path("/init")
    public Response initialize(Map<String, String> payload) {
        String passcode = payload.get("passcode");
        if (passcode == null || passcode.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Passcode is required")).build();
        }
        if (vaultEngine.isInitialized()) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of("error", "Vault already initialized")).build();
        }
        vaultEngine.initializeVault(passcode);
        return Response.ok(Map.of("success", true)).build();
    }

    @POST
    @Path("/unseal")
    public Response unseal(Map<String, String> payload) {
        String passcode = payload.get("passcode");
        if (passcode == null || passcode.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Passcode is required")).build();
        }
        if (!vaultEngine.isInitialized()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Vault not initialized")).build();
        }
        boolean success = vaultEngine.unsealVault(passcode);
        if (success) {
            return Response.ok(Map.of("success", true)).build();
        } else {
            return Response.status(Response.Status.UNAUTHORIZED).entity(Map.of("error", "Invalid passcode")).build();
        }
    }
    
    @POST
    @Path("/lock")
    public Response lock() {
        vaultEngine.lockVault();
        return Response.ok(Map.of("success", true)).build();
    }
}
