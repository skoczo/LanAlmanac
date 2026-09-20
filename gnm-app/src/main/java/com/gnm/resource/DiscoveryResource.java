package com.gnm.resource;

import com.gnm.discovery.DiscoveryModuleManager;
import com.gnm.discovery.model.DiscoveryModuleStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/discovery")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DiscoveryResource {

    @Inject
    DiscoveryModuleManager moduleManager;

    @GET
    @Path("/modules")
    public List<DiscoveryModuleStatus> getModules() {
        return moduleManager.getAllModuleStatuses();
    }

    @GET
    @Path("/modules/{id}")
    public Response getModule(@PathParam("id") String id) {
        DiscoveryModuleStatus status = moduleManager.getModuleStatus(id);
        if (status == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(status).build();
    }

    @POST
    @Path("/modules/{id}/toggle")
    public Response toggleModule(@PathParam("id") String id, ToggleRequest req) {
        boolean enabled = req != null && req.enabled;
        boolean success = moduleManager.toggleModule(id, enabled);
        if (!success) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(moduleManager.getModuleStatus(id)).build();
    }

    @POST
    @Path("/modules/{id}/trigger")
    public Response triggerScan(@PathParam("id") String id) {
        boolean success = moduleManager.triggerScan(id);
        if (!success) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Module " + id + " not found or disabled.")
                    .build();
        }
        return Response.accepted().entity("Scan for module " + id + " started.").build();
    }

    public static class ToggleRequest {
        public boolean enabled;
    }
}
