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

import com.gnm.model.Credential;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.DeviceStatusHistory;
import com.gnm.model.NetworkService;
import com.gnm.model.NetworkSighting;
import com.gnm.model.Telemetry;
import com.gnm.model.FingerprintCorrelationEvent;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.DeviceType;
import com.gnm.discovery.NetworkSightingQueue;
import com.gnm.fingerprint.FingerprintEngine;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.time.Instant;

@Path("/api/devices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("gnm-admin")
public class DeviceResource {

    @Inject
    NetworkSightingQueue sightingQueue;

    @Inject
    FingerprintEngine fingerprintEngine;

    @GET
    @Transactional
    public List<PhysicalDevice> getAllDevices() {
        // Eagerly fetch identities and fingerprints to avoid LazyInitializationException
        List<PhysicalDevice> devices = PhysicalDevice.list("select distinct d from PhysicalDevice d " +
                "left join fetch d.identities " +
                "left join fetch d.fingerprints " +
                "left join fetch d.services " +
                "order by d.displayName");
        
        // Force load lazy collections within the active transaction
        for (PhysicalDevice d : devices) {
            initializeLazyCollections(d);
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
            if (device.services != null) device.services.size();
            if (device.labels != null) device.labels.size();
        }
    }

    /**
     * Test/admin endpoint: directly triggers updateProbeCounters with a provided set of live IPs.
     * Devices whose current IP is NOT in liveIps will have their consecutiveMissedProbes incremented.
     * This allows E2E tests to verify offline detection without waiting for the ICMP scheduler cycle.
     *
     * Body: JSON array of IP address strings that responded in the probe cycle.
     * Example: [] means all online devices are considered to have missed the cycle.
     */
    @POST
    @Path("/probe-update")
    public Response triggerProbeUpdate(java.util.List<String> liveIpList) {
        java.util.Set<String> liveIps = liveIpList != null
            ? new java.util.HashSet<>(liveIpList)
            : java.util.Collections.emptySet();
        fingerprintEngine.updateProbeCounters(liveIps);
        return Response.accepted(Map.of("message", "Probe counters updated", "liveIpCount", liveIps.size())).build();
    }

    @GET
    @Path("/{id}/services")
    @Transactional
    public List<NetworkService> getDeviceServices(@PathParam("id") UUID id) {
        return NetworkService.list("physicalDevice.id", id);
    }

    @POST
    @Path("/{id}/services")
    @Transactional
    public Response addService(@PathParam("id") UUID id, NetworkService service) {
        PhysicalDevice device = PhysicalDevice.findById(id);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        if (service.credential != null && service.credential.id != null) {
            Credential cred = Credential.findById(service.credential.id);
            if (cred != null && cred.physicalDevice.id.equals(id)) {
                service.credential = cred;
            } else {
                service.credential = null;
            }
        }
        
        service.physicalDevice = device;
        service.firstSeen = Instant.now();
        service.lastSeen = Instant.now();
        service.persist();
        return Response.ok(service).build();
    }

    @PUT
    @Path("/{id}/services/{serviceId}")
    @Transactional
    public Response updateService(@PathParam("id") UUID id, @PathParam("serviceId") UUID serviceId, NetworkService payload) {
        NetworkService service = NetworkService.findById(serviceId);
        if (service == null || !service.physicalDevice.id.equals(id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        
        if (payload.label != null) service.label = payload.label;
        if (payload.serviceType != null) service.serviceType = payload.serviceType;
        if (payload.protocol != null) service.protocol = payload.protocol;
        if (payload.port != null) service.port = payload.port;
        if (payload.manageable != null) service.manageable = payload.manageable;
        
        if (payload.credential != null && payload.credential.id != null) {
            Credential cred = Credential.findById(payload.credential.id);
            if (cred != null && cred.physicalDevice.id.equals(id)) {
                service.credential = cred;
            }
        } else if (payload.credential == null) {
            service.credential = null;
        }

        service.lastSeen = Instant.now();
        service.persist();
        return Response.ok(service).build();
    }

    @DELETE
    @Path("/services/{serviceId}")
    @Transactional
    public Response deleteService(@PathParam("serviceId") UUID serviceId) {
        NetworkService service = NetworkService.findById(serviceId);
        if (service != null) {
            service.delete();
        }
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response deleteDevice(@PathParam("id") UUID id) {
        PhysicalDevice device = PhysicalDevice.findById(id);
        if (device != null) {
            device.delete();
            return Response.noContent().build();
        }
        return Response.status(Response.Status.NOT_FOUND).build();
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
    @Path("/{id}/services/{serviceId}/trust-ssh-key")
    @Transactional
    public Response trustSshKey(@PathParam("id") UUID id, @PathParam("serviceId") UUID serviceId) {
        NetworkService service = NetworkService.findById(serviceId);
        if (service == null || !service.physicalDevice.id.equals(id)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (service.sshHostKey != null && !service.sshHostKey.isEmpty()) {
            service.sshHostKeyTrusted = true;
            service.persist();
            
            // Reload the device to return it
            PhysicalDevice device = PhysicalDevice.findById(id);
            initializeLazyCollections(device);
            return Response.ok(device).build();
        }
        return Response.status(Response.Status.BAD_REQUEST).entity("No SSH key to trust").build();
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

    @POST
    @Path("/discover")
    public Response discoverDevice(Map<String, String> payload) {
        String ip = payload.get("ipAddress");
        if (ip == null || ip.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("IP address is required").build();
        }
        
        // Run ping in virtual thread to avoid blocking the REST worker
        Thread.startVirtualThread(() -> {
            try {
                // Try system ping first
                Process p = new ProcessBuilder("ping", "-c", "1", "-W", "1", ip).start();
                boolean reachable = (p.waitFor() == 0);
                
                if (!reachable) {
                    int[] ports = { 22, 80, 443, 137, 445 };
                    for (int port : ports) {
                        try (java.net.Socket socket = new java.net.Socket()) {
                            socket.connect(new java.net.InetSocketAddress(ip, port), 500);
                            reachable = true;
                            break;
                        } catch (java.io.IOException e) {
                            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("refused")) {
                                reachable = true;
                                break;
                            }
                        }
                    }
                }

                if (reachable) {
                    NetworkSighting sighting = new NetworkSighting();
                    sighting.ipAddress = ip;
                    sighting.macAddress = "00:00:00:00:00:00"; // Will be updated by fingerprinting
                    sighting.source = "MANUAL_DISCOVERY";
                    sighting.observedAt = Instant.now();
                    sighting.rawMetadata = "{}";
                    sightingQueue.offer(sighting);
                }
            } catch (Exception e) {
                // Ignored
            }
        });
        
        return Response.accepted().entity(Map.of("message", "Discovery initiated for " + ip)).build();
    }

    @POST
    @Path("/manual")
    @Transactional
    public Response addDeviceManually(Map<String, String> payload) {
        String ip = payload.get("ipAddress");
        String name = payload.get("displayName");
        
        if (ip == null || name == null || ip.isBlank() || name.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("IP address and display name are required").build();
        }

        PhysicalDevice device = new PhysicalDevice();
        device.displayName = name;
        
        String typeStr = payload.get("deviceType");
        if (typeStr != null && !typeStr.isBlank()) {
            try {
                device.deviceType = DeviceType.valueOf(typeStr.toUpperCase());
            } catch (Exception e) {
                device.deviceType = DeviceType.UNKNOWN;
            }
        } else {
            device.deviceType = DeviceType.UNKNOWN;
        }
        
        device.locationNote = payload.get("locationNote");
        device.managementState = ManagementState.MANAGED;
        device.status = DeviceStatus.OFFLINE; // Initial state
        device.firstSeen = Instant.now();
        device.lastSeen = Instant.now();
        device.persist();

        NetworkIdentity identity = new NetworkIdentity();
        identity.physicalDevice = device;
        identity.ipAddress = ip;
        identity.macAddress = payload.getOrDefault("macAddress", "00:00:00:00:00:00");
        identity.firstSeen = Instant.now();
        identity.lastSeen = Instant.now();
        identity.current = true;
        identity.persist();

        return Response.status(Response.Status.CREATED).entity(device).build();
    }

    @GET
    @Path("/{id}/correlation-history")
    @Transactional
    public List<FingerprintCorrelationEvent> getCorrelationHistory(@PathParam("id") UUID id) {
        return FingerprintCorrelationEvent.list("physicalDevice.id = ?1 order by timestamp desc", id);
    }

    @GET
    @Path("/{id}/status-history")
    @Transactional
    public List<DeviceStatusHistory> getDeviceStatusHistory(@PathParam("id") UUID id) {
        return DeviceStatusHistory.find("physicalDevice.id = ?1 order by timestamp desc", id).list();
    }
}
