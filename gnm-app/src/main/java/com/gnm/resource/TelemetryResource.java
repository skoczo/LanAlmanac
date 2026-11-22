package com.gnm.resource;

import com.gnm.model.Telemetry;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/devices/{id}/telemetry")
@Produces(MediaType.APPLICATION_JSON)
public class TelemetryResource {

    @GET
    public List<Telemetry> getTelemetry(@PathParam("id") UUID deviceId) {
        return Telemetry.find("id.physicalDeviceId = ?1 ORDER BY id.time ASC", deviceId).list();
    }
}
