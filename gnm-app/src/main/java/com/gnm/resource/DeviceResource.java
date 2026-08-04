package com.gnm.resource;

import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import com.gnm.model.enums.ManagementState;

import com.gnm.model.PhysicalDevice;
import com.gnm.model.Telemetry;

@Path("/api/devices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("gnm-admin")
public class DeviceResource {

    @GET
    @Transactional
    public List<PhysicalDevice> getAllDevices() {
        // Eagerly fetch identities and fingerprints to avoid LazyInitializationException
        List<PhysicalDevice> devices = PhysicalDevice.list("select distinct d from PhysicalDevice d " +
                "left join fetch d.identities " +
                "left join fetch d.fingerprints " +
                "order by d.displayName");
        
        // Force load lazy credentials within the active transaction
        for (PhysicalDevice d : devices) {
            d.credentials.size();
        }
        
        return devices;
    }

    @GET
    @Path("/{id}")
    @Transactional
    public Response getDeviceById(@PathParam("id") UUID id) {
        PhysicalDevice device = PhysicalDevice.findById(id);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Device not found")
                    .build();
        }
        // Force load lazy collections within transaction
        initializeLazyCollections(device);
        
        return Response.ok(device).build();
    }

    private void initializeLazyCollections(PhysicalDevice device) {
        if (device != null) {
            if (device.identities != null) device.identities.size();
            if (device.fingerprints != null) device.fingerprints.size();
            if (device.credentials != null) device.credentials.size();
            if (device.labels != null) device.labels.size();
        }
    }
    @GET
    @Path("/{id}/telemetry")
    @Transactional
    public List<Telemetry> getDeviceTelemetry(@PathParam("id") UUID id) {
        return Telemetry.list("id.physicalDeviceId = ?1 order by id.time asc", id);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public Response updateDeviceDetails(@PathParam("id") UUID id, Map<String, String> payload) {
        PhysicalDevice device = PhysicalDevice.findById(id);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (payload.containsKey("displayName")) device.displayName = payload.get("displayName");
        if (payload.containsKey("deviceType")) {
            try {
                device.deviceType = com.gnm.model.enums.DeviceType.valueOf(payload.get("deviceType").toUpperCase());
            } catch (Exception ignored) {}
        }
        if (payload.containsKey("manufacturer")) device.manufacturer = payload.get("manufacturer");
        if (payload.containsKey("model")) device.model = payload.get("model");
        if (payload.containsKey("locationNote")) device.locationNote = payload.get("locationNote");
        if (payload.containsKey("osFamily")) device.osFamily = payload.get("osFamily");
        if (payload.containsKey("osVersion")) device.osVersion = payload.get("osVersion");
        
        device.persist();
        initializeLazyCollections(device);
        return Response.ok(device).build();
    }

    @PUT
    @Path("/{id}/state")
    @Transactional
    public Response updateDeviceState(@PathParam("id") UUID id, Map<String, String> payload) {
        PhysicalDevice device = PhysicalDevice.findById(id);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        String stateStr = payload.get("managementState");
        if (stateStr != null) {
            try {
                device.managementState = ManagementState.valueOf(stateStr.toUpperCase());
                device.persist();
                initializeLazyCollections(device);
                return Response.ok(device).build();
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST).entity("Invalid ManagementState").build();
            }
        }
        return Response.status(Response.Status.BAD_REQUEST).build();
    }

    @PUT
    @Path("/{id}/labels")
    @Transactional
    public Response updateDeviceLabels(@PathParam("id") UUID id, List<String> labels) {
        PhysicalDevice device = PhysicalDevice.findById(id);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        device.labels.clear();
        if (labels != null) {
            device.labels.addAll(labels);
        }
        device.persist();
        initializeLazyCollections(device);
        return Response.ok(device).build();
    }
}
