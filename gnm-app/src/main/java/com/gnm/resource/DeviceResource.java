package com.gnm.resource;

import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

import com.gnm.model.PhysicalDevice;
import com.gnm.model.Telemetry;

@Path("/api/devices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("gnm-admin")
public class DeviceResource {

    @GET
    @Transactional
    public List<PhysicalDevice> getAllDevices() {
        // Eagerly fetch identities and fingerprints to avoid LazyInitializationException
        return PhysicalDevice.list("select distinct d from PhysicalDevice d " +
                "left join fetch d.identities " +
                "left join fetch d.fingerprints " +
                "order by d.displayName");
    }

    @GET
    @Path("/{id}")
    @Transactional
    public Response getDeviceById(@PathParam("id") UUID id) {
        PhysicalDevice device = PhysicalDevice.findById(id);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Device not found")
                    .build();
        }
        // Force load lazy collections within transaction
        device.identities.size();
        device.fingerprints.size();
        device.credentials.size();
        
        return Response.ok(device).build();
    }

    @GET
    @Path("/{id}/telemetry")
    @Transactional
    public List<Telemetry> getDeviceTelemetry(@PathParam("id") UUID id) {
        return Telemetry.list("id.physicalDeviceId = ?1 order by id.time asc", id);
    }
}
