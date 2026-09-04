package com.gnm.resource;

import com.gnm.auth.PasswordService;
import com.gnm.model.GnmUser;
import io.smallrye.jwt.build.Jwt;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LocalAuthResource {

    @Inject
    PasswordService passwordService;

    public static class LoginRequest {
        public String username;
        public String password;
    }

    public static class LoginResponse {
        public String token;
        public String username;
        public List<String> roles;
        public boolean mustChangePassword;

        public LoginResponse(String token, String username, List<String> roles, boolean mustChangePassword) {
            this.token = token;
            this.username = username;
            this.roles = roles;
            this.mustChangePassword = mustChangePassword;
        }
    }

    public static class ChangePasswordRequest {
        public String currentPassword;
        public String newPassword;
    }

    @POST
    @Path("/login")
    @Transactional
    public Response login(LoginRequest request) {
        if (request == null || request.username == null || request.password == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Missing username or password\"}")
                    .build();
        }

        GnmUser user = GnmUser.findByUsername(request.username);
        if (user == null || !user.enabled) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Invalid username or password\"}")
                    .build();
        }

        if (!passwordService.verifyPassword(request.password, user.passwordHash)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Invalid username or password\"}")
                    .build();
        }

        // Generate signed JWT token
        String token = Jwt.issuer("https://gnm.local")
                .upn(user.username)
                .groups(new HashSet<>(List.of(user.role)))
                .expiresIn(Duration.ofHours(24))
                .sign();

        return Response.ok(new LoginResponse(
                token,
                user.username,
                List.of(user.role),
                user.mustChangePassword
        )).build();
    }

    @POST
    @Path("/change-password")
    @Transactional
    public Response changePassword(@HeaderParam("Authorization") String authHeader, ChangePasswordRequest request) {
        if (request == null || request.currentPassword == null || request.newPassword == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Missing current or new password\"}")
                    .build();
        }

        if (request.newPassword.length() < 8) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"New password must be at least 8 characters\"}")
                    .build();
        }

        // Extract username from JWT token
        String username = extractUsernameFromToken(authHeader);
        if (username == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Invalid or missing authentication token\"}")
                    .build();
        }

        GnmUser user = GnmUser.findByUsername(username);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"User not found\"}")
                    .build();
        }

        if (!passwordService.verifyPassword(request.currentPassword, user.passwordHash)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Current password is incorrect\"}")
                    .build();
        }

        user.passwordHash = passwordService.hashPassword(request.newPassword);
        user.mustChangePassword = false;
        user.persist();

        // Issue a new token since the old password-change-flagged session should be refreshed
        String newToken = Jwt.issuer("https://gnm.local")
                .upn(user.username)
                .groups(new HashSet<>(List.of(user.role)))
                .expiresIn(Duration.ofHours(24))
                .sign();

        return Response.ok(new LoginResponse(
                newToken,
                user.username,
                List.of(user.role),
                false
        )).build();
    }

    private String extractUsernameFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            String token = authHeader.substring(7);
            // Decode the JWT payload (middle part) to extract upn
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
            // Simple JSON parsing for upn field
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(payload);
            if (node.has("upn")) {
                return node.get("upn").asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
