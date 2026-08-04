package com.gnm.resource;

import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LocalAuthResource {

    @ConfigProperty(name = "gnm.auth.local.username", defaultValue = "admin")
    String adminUsername;

    @ConfigProperty(name = "gnm.auth.local.password", defaultValue = "admin")
    String adminPassword;

    public static class LoginRequest {
        public String username;
        public String password;
    }

    public static class LoginResponse {
        public String token;
        public String username;
        public List<String> roles;

        public LoginResponse(String token, String username, List<String> roles) {
            this.token = token;
            this.username = username;
            this.roles = roles;
        }
    }

    @POST
    @Path("/login")
    public Response login(LoginRequest request) {
        if (request == null || request.username == null || request.password == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Missing username or password")
                    .build();
        }

        if (adminUsername.equals(request.username) && adminPassword.equals(request.password)) {
            // Generate signed JWT token
            String token = Jwt.issuer("https://gnm.local")
                    .upn(request.username)
                    .groups(new HashSet<>(List.of("gnm-admin")))
                    .expiresIn(Duration.ofHours(24))
                    .sign();

            return Response.ok(new LoginResponse(token, request.username, List.of("gnm-admin"))).build();
        }

        return Response.status(Response.Status.UNAUTHORIZED)
                .entity("Invalid username or password")
                .build();
    }
}
