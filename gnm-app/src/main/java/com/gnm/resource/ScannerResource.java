package com.gnm.resource;

import com.gnm.dto.ScanProgress;
import com.gnm.model.PhysicalDevice;
import com.gnm.service.scanner.BackgroundScannerService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import java.util.List;

@Path("/api/scanner")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ScannerResource {

    @Inject
    BackgroundScannerService scannerService;

    @GET
    @Path("/progress")
    public ScanProgress getProgress() {
        return scannerService.getProgress();
    }
    
    @POST
    @Path("/scan/{deviceId}")
    @Transactional
    public Response scanDevice(@PathParam("deviceId") UUID deviceId) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        scannerService.enqueueDevice(deviceId);
        return Response.accepted().build();
    }
    
    @POST
    @Path("/scan-all")
    @Transactional
    public Response scanAllPending() {
        List<PhysicalDevice> devices = PhysicalDevice.find("portScanState = 'PENDING'").list();
        for (PhysicalDevice device : devices) {
            scannerService.enqueueDevice(device.id);
        }
        return Response.accepted().entity(devices.size() + " devices queued for scanning").build();
    }
}
