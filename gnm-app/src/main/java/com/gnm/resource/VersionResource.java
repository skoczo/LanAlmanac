package com.gnm.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Path("/api/version")
@Produces(MediaType.APPLICATION_JSON)
public class VersionResource {

    @ConfigProperty(name = "gnm.app.version", defaultValue = "1.0.0")
    String appVersion;

    @ConfigProperty(name = "quarkus.application.name", defaultValue = "gnm-app")
    String appName;

    @GET
    public Map<String, Object> getVersion() {
        Map<String, Object> response = new HashMap<>();
        response.put("name", appName);
        response.put("version", appVersion);
        response.put("status", "UP");
        response.put("timestamp", Instant.now().toString());
        return response;
    }
}
