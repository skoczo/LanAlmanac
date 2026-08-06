package com.gnm.resource;

import com.gnm.model.PhysicalDevice;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/proxy")
public class WebProxyResource {

    private static final Logger log = Logger.getLogger(WebProxyResource.class);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @GET
    @Path("/{deviceId}/{path: .*}")
    @Transactional
    public Response proxyGet(@PathParam("deviceId") UUID deviceId, @PathParam("path") String path, @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return proxyRequest(deviceId, path, "GET", null, uriInfo, headers);
    }

    @POST
    @Path("/{deviceId}/{path: .*}")
    @Transactional
    public Response proxyPost(@PathParam("deviceId") UUID deviceId, @PathParam("path") String path, InputStream body, @Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return proxyRequest(deviceId, path, "POST", body, uriInfo, headers);
    }

    private Response proxyRequest(UUID deviceId, String path, String method, InputStream body, UriInfo uriInfo, HttpHeaders headers) {
        PhysicalDevice device = PhysicalDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Device not found").build();
        }

        String ipAddress = device.identities.stream()
                .filter(id -> id.current)
                .map(id -> id.ipAddress)
                .findFirst()
                .orElse(null);

        if (ipAddress == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Device has no IP address").build();
        }

        String queryString = uriInfo.getRequestUri().getRawQuery();
        String targetUrl = "http://" + ipAddress + "/" + (path == null ? "" : path) + (queryString != null ? "?" + queryString : "");

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofInputStream(() -> body));

            for (Map.Entry<String, List<String>> header : headers.getRequestHeaders().entrySet()) {
                String key = header.getKey().toLowerCase();
                // Avoid forwarding restricted headers
                if (!key.equals("host") && !key.equals("connection") && !key.equals("authorization") && !key.equals("content-length")) {
                    for (String value : header.getValue()) {
                        requestBuilder.header(header.getKey(), value);
                    }
                }
            }

            HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            Response.ResponseBuilder responseBuilder = Response.status(response.statusCode())
                    .entity(response.body());

            for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
                String key = header.getKey().toLowerCase();
                if (!key.equals("transfer-encoding") && !key.equals("connection")) {
                    for (String value : header.getValue()) {
                        responseBuilder.header(header.getKey(), value);
                    }
                }
            }

            return responseBuilder.build();
        } catch (Exception e) {
            log.error("Proxy error: " + targetUrl, e);
            return Response.serverError().entity("Proxy error: " + e.getMessage()).build();
        }
    }
}
