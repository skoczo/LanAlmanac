package com.gnm.resource;

import com.gnm.model.ThreatEvent;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

@Path("/api/threats")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ThreatResource {

    @GET
    public List<ThreatEvent> getThreats() {
        return ThreatEvent.list("ORDER BY detectedAt DESC");
    }

    @PUT
    @Path("/{id}/resolve")
    @Transactional
    public ThreatEvent resolveThreat(@PathParam("id") UUID id) {
        ThreatEvent threat = ThreatEvent.findById(id);
        if (threat != null) {
            threat.resolved = true;
            threat.persist();
        }
        return threat;
    }
}
