package com.gnm.resource;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.gnm.dto.backup.LanAlmanacBackup;
import com.gnm.service.BackupService;

@Path("/api/backup")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("admin")
public class BackupResource {

    @Inject
    BackupService backupService;

    @GET
    @Path("/export")
    public Response exportData(@QueryParam("includeSecrets") @DefaultValue("false") boolean includeSecrets) {
        LanAlmanacBackup backup = backupService.exportData(includeSecrets);
        return Response.ok(backup)
                .header("Content-Disposition", "attachment; filename=\"lanalmanac_backup_" + backup.exportDate.toString() + ".json\"")
                .build();
    }

    @POST
    @Path("/import")
    public Response importData(LanAlmanacBackup backup) {
        if (backup == null || backup.version == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Invalid backup payload").build();
        }
        
        try {
            backupService.importData(backup);
            return Response.ok().entity("{\"status\":\"success\"}").build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }
    }
}
