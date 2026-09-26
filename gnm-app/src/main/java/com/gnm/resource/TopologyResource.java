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
        List<PhysicalDevice> devices = PhysicalDevice.list("managementState", ManagementState.MANAGED);
        List<NetworkLink> links = NetworkLink.listAll();

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        
        java.util.Set<String> linkedDeviceIds = new java.util.HashSet<>();

        for (PhysicalDevice device : devices) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", device.id.toString());
            
            Map<String, Object> data = new HashMap<>();
            data.put("label", device.displayName);
            data.put("type", device.deviceType.name());
            data.put("status", device.status.name());
            
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

        // Add virtual hierarchical links
        PhysicalDevice mainRouter = devices.stream()
            .filter(d -> d.deviceType == com.gnm.model.enums.DeviceType.ROUTER || d.displayName.toLowerCase().contains("router") || d.displayName.toLowerCase().contains("openwrt"))
            .findFirst()
            .orElse(null);
            
        PhysicalDevice proxmox = devices.stream()
            .filter(d -> d.displayName.toLowerCase().contains("proxmox") || d.displayName.toLowerCase().contains("server") || d.deviceType == com.gnm.model.enums.DeviceType.SERVER)
            .findFirst()
            .orElse(null);

        for (PhysicalDevice device : devices) {
            boolean isVirtualLinkNeeded = !linkedDeviceIds.contains(device.id.toString());
            
            if (isVirtualLinkNeeded) {
                boolean isContainer = device.displayName.toLowerCase().contains("docker") || device.displayName.toLowerCase().contains("container");
                
                if (isContainer && proxmox != null && !device.id.equals(proxmox.id)) {
                    Map<String, Object> edge = new HashMap<>();
                    edge.put("id", "virtual-" + device.id.toString());
                    edge.put("source", proxmox.id.toString());
                    edge.put("target", device.id.toString());
                    edge.put("label", "Virtual (Host)");
                    edge.put("type", "smoothstep");
                    edges.add(edge);
                    linkedDeviceIds.add(device.id.toString());
                } else if (mainRouter != null && !device.id.equals(mainRouter.id)) {
                    Map<String, Object> edge = new HashMap<>();
                    edge.put("id", "virtual-" + device.id.toString());
                    edge.put("source", mainRouter.id.toString());
                    edge.put("target", device.id.toString());
                    edge.put("label", "Virtual Link");
                    edge.put("type", "smoothstep");
                    edges.add(edge);
                    linkedDeviceIds.add(device.id.toString());
                }
            }
        }

        // Ensure proxmox is connected to mainRouter if neither is linked to each other
        if (proxmox != null && mainRouter != null && !proxmox.id.equals(mainRouter.id)) {
            if (!linkedDeviceIds.contains(proxmox.id.toString())) {
                Map<String, Object> edge = new HashMap<>();
                edge.put("id", "virtual-proxmox-router");
                edge.put("source", mainRouter.id.toString());
                edge.put("target", proxmox.id.toString());
                edge.put("label", "Virtual Link");
                edge.put("type", "smoothstep");
                edges.add(edge);
            }
        }

        Map<String, Object> graph = new HashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);

        return Response.ok(graph).build();
    }
}
