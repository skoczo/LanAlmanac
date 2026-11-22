package com.gnm.resource;

import com.gnm.model.Telemetry;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Path("/api/devices/{id}/telemetry")
@Produces(MediaType.APPLICATION_JSON)
public class TelemetryResource {

    @GET
    public List<Telemetry> getTelemetry(
            @PathParam("id") UUID deviceId,
            @QueryParam("hours") @DefaultValue("24") int hours) {
        if (hours <= 0) {
            return Telemetry.find("id.physicalDeviceId = ?1 ORDER BY id.time ASC", deviceId).list();
        }
        Instant since = Instant.now().minus(Duration.ofHours(hours));
        return Telemetry.find(
                        "id.physicalDeviceId = ?1 and id.time >= ?2 ORDER BY id.time ASC",
                        deviceId,
                        since)
                .list();
    }
}

