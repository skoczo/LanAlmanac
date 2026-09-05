package com.gnm.resource;

import com.gnm.service.BackupService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

@Path("/api/backup")
@RolesAllowed("admin")
public class BackupResource {

    private static final Logger LOG = Logger.getLogger(BackupResource.class);

    @Inject
    BackupService backupService;

    @GET
    @Path("/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadBackup(@QueryParam("password") String password) {
        if (password == null || password.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Backup password is required").build();
        }

        try {
            java.nio.file.Path encryptedBackup = backupService.createBackup(password);
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String filename = "gnm_backup_" + timestamp + ".gnmbak";

            StreamingOutput stream = output -> {
                try (InputStream is = Files.newInputStream(encryptedBackup)) {
                    is.transferTo(output);
                } finally {
                    Files.deleteIfExists(encryptedBackup);
                }
            };

            return Response.ok(stream)
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to create backup", e);
            return Response.serverError().entity("Failed to create backup: " + e.getMessage()).build();
        }
    }

    @POST
    @Path("/restore")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response restoreBackup(
            @RestForm("file") FileUpload file,
            @RestForm("password") String password) {
        
        if (file == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Backup file is required").build();
        }
        if (password == null || password.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Backup password is required").build();
        }

        try {
            // restoreBackup will call System.exit(0) on success, so we won't actually return a response on success
            // in a standard way, but we will start it in a new thread to allow the HTTP response to be sent to the client.
            
            Thread restoreThread = new Thread(() -> {
                try {
                    // Give the client a second to receive the 202 Accepted response
                    Thread.sleep(1000);
                    backupService.restoreBackup(file.uploadedFile(), password);
                } catch (Exception e) {
                    LOG.error("Restore failed!", e);
                }
            });
            restoreThread.start();

            return Response.accepted().entity("{\"message\": \"Restore initiated. System will restart shortly.\"}").build();
        } catch (Exception e) {
            LOG.error("Failed to initiate restore", e);
            return Response.serverError().entity("Failed to initiate restore: " + e.getMessage()).build();
        }
    }
}
