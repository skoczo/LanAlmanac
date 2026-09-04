package com.gnm.resource;

import com.gnm.auth.PasswordService;
import com.gnm.model.GnmUser;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@Path("/api/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject
    PasswordService passwordService;

    // --- DTOs ---

    public static class UserDto {
        public UUID id;
        public String username;
        public String displayName;
        public String role;
        public boolean mustChangePassword;
        public boolean enabled;
        public String createdAt;
        public String updatedAt;

        public static UserDto from(GnmUser user) {
            UserDto dto = new UserDto();
            dto.id = user.id;
            dto.username = user.username;
            dto.displayName = user.displayName;
            dto.role = user.role;
            dto.mustChangePassword = user.mustChangePassword;
            dto.enabled = user.enabled;
            dto.createdAt = user.createdAt != null ? user.createdAt.toString() : null;
            dto.updatedAt = user.updatedAt != null ? user.updatedAt.toString() : null;
            return dto;
        }
    }

    public static class CreateUserRequest {
        public String username;
        public String password;
        public String displayName;
        public String role;
    }

    public static class UpdateUserRequest {
        public String displayName;
        public String role;
        public Boolean enabled;
    }

    public static class ResetPasswordRequest {
        public String newPassword;
    }

    // --- Endpoints ---

    @GET
    public List<UserDto> listUsers() {
        return GnmUser.<GnmUser>listAll().stream()
                .map(UserDto::from)
                .toList();
    }

    @POST
    @Transactional
    public Response createUser(CreateUserRequest request) {
        if (request == null || request.username == null || request.username.isBlank()
                || request.password == null || request.password.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Username and password are required\"}")
                    .build();
        }

        if (request.password.length() < 8) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Password must be at least 8 characters\"}")
                    .build();
        }

        if (GnmUser.findByUsername(request.username) != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\":\"Username already exists\"}")
                    .build();
        }

        String role = request.role != null ? request.role : "gnm-viewer";
        if (!isValidRole(role)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid role. Must be gnm-admin, gnm-operator, or gnm-viewer\"}")
                    .build();
        }

        GnmUser user = new GnmUser();
        user.username = request.username.trim();
        user.passwordHash = passwordService.hashPassword(request.password);
        user.displayName = request.displayName;
        user.role = role;
        user.mustChangePassword = true;
        user.enabled = true;
        user.persist();

        return Response.status(Response.Status.CREATED).entity(UserDto.from(user)).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response updateUser(@PathParam("id") UUID id, UpdateUserRequest request) {
        GnmUser user = GnmUser.findById(id);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"User not found\"}")
                    .build();
        }

        if (request.displayName != null) {
            user.displayName = request.displayName;
        }

        if (request.role != null) {
            if (!isValidRole(request.role)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"Invalid role. Must be gnm-admin, gnm-operator, or gnm-viewer\"}")
                        .build();
            }
            user.role = request.role;
        }

        if (request.enabled != null) {
            user.enabled = request.enabled;
        }

        user.persist();
        return Response.ok(UserDto.from(user)).build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteUser(@PathParam("id") UUID id, @HeaderParam("Authorization") String authHeader) {
        GnmUser user = GnmUser.findById(id);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"User not found\"}")
                    .build();
        }

        // Prevent deleting the last admin
        long adminCount = GnmUser.count("role", "gnm-admin");
        if ("gnm-admin".equals(user.role) && adminCount <= 1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Cannot delete the last admin user\"}")
                    .build();
        }

        user.delete();
        return Response.noContent().build();
    }

    @PUT
    @Path("/{id}/reset-password")
    @Transactional
    public Response resetPassword(@PathParam("id") UUID id, ResetPasswordRequest request) {
        GnmUser user = GnmUser.findById(id);
        if (user == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"User not found\"}")
                    .build();
        }

        if (request == null || request.newPassword == null || request.newPassword.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"New password is required\"}")
                    .build();
        }

        if (request.newPassword.length() < 8) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Password must be at least 8 characters\"}")
                    .build();
        }

        user.passwordHash = passwordService.hashPassword(request.newPassword);
        user.mustChangePassword = true;
        user.persist();

        return Response.ok(UserDto.from(user)).build();
    }

    private boolean isValidRole(String role) {
        return "gnm-admin".equals(role) || "gnm-operator".equals(role) || "gnm-viewer".equals(role);
    }
}
