package com.gnm.resource;

import com.gnm.model.NetworkLink;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.ManagementState;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/topology")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("gnm-admin")
public class TopologyResource {

    @GET
    @Transactional
    public Response getTopologyGraph() {
        // Fetch only MANAGED devices and any device that is connected via a link
        List<NetworkLink> links = NetworkLink.listAll();
        java.util.Set<java.util.UUID> includedDeviceIds = new java.util.HashSet<>();
        
        List<PhysicalDevice> managedDevices = PhysicalDevice.list("managementState", ManagementState.MANAGED);
        for (PhysicalDevice d : managedDevices) {
            includedDeviceIds.add(d.id);
        }
        
        for (NetworkLink link : links) {
            if (link.sourceDevice != null) includedDeviceIds.add(link.sourceDevice.id);
            if (link.targetDevice != null) includedDeviceIds.add(link.targetDevice.id);
        }

        List<PhysicalDevice> devices = new ArrayList<>();
        if (!includedDeviceIds.isEmpty()) {
            devices = PhysicalDevice.list("id in ?1", includedDeviceIds);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        
        java.util.Set<String> linkedDeviceIds = new java.util.HashSet<>();

        for (PhysicalDevice device : devices) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", device.id.toString());
            
            Map<String, Object> data = new HashMap<>();
            data.put("label", device.displayName != null ? device.displayName : "Unknown");
            data.put("type", device.deviceType != null ? device.deviceType.name() : "UNKNOWN");
            data.put("status", device.status != null ? device.status.name() : "OFFLINE");
            
            node.put("data", data);
            
            // Layout handled on frontend
            Map<String, Integer> position = new HashMap<>();
            position.put("x", 0);
            position.put("y", 0);
            node.put("position", position);
            
            nodes.add(node);
        }

        for (NetworkLink link : links) {
            Map<String, Object> edge = new HashMap<>();
            edge.put("id", link.id.toString());
            edge.put("source", link.sourceDevice.id.toString());
            edge.put("target", link.targetDevice.id.toString());
            edge.put("label", link.sourceInterface + " -> " + link.targetInterface);
            edge.put("type", "smoothstep");
            
            edges.add(edge);
            linkedDeviceIds.add(link.sourceDevice.id.toString());
            linkedDeviceIds.add(link.targetDevice.id.toString());
        }



        Map<String, Object> graph = new HashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);

        return Response.ok(graph).build();
    }
}
