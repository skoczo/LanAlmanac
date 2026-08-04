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

        for (PhysicalDevice device : devices) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", device.id.toString());
            
            Map<String, Object> data = new HashMap<>();
            data.put("label", device.displayName);
            data.put("type", device.deviceType.name());
            data.put("status", device.status.name());
            
            node.put("data", data);
            
            // React Flow requires coordinates, but we will let a layout engine (like Dagre) handle this on the frontend
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
        }

        Map<String, Object> graph = new HashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);

        return Response.ok(graph).build();
    }
}
