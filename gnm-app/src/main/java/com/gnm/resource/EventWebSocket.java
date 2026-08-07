package com.gnm.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.websockets.next.*;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gnm.fingerprint.FingerprintEngine.DeviceEvent;
import com.gnm.model.ThreatEvent;

@WebSocket(path = "/ws/events")
@ApplicationScoped
public class EventWebSocket {

    private static final Logger LOG = Logger.getLogger(EventWebSocket.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final java.util.Set<WebSocketConnection> activeConnections = new java.util.concurrent.CopyOnWriteArraySet<>();

    @OnOpen
    public void onOpen(WebSocketConnection conn) {
        LOG.info("WebSocket connection opened: " + conn.id());
        activeConnections.add(conn);
    }

    @OnClose
    public void onClose(WebSocketConnection conn) {
        LOG.info("WebSocket connection closed: " + conn.id());
        activeConnections.remove(conn);
    }

    public void onDeviceEvent(@Observes DeviceEvent event) {
        LOG.info("Observing DeviceEvent: " + event.type + " for device: " + event.displayName);
        try {
            String json = MAPPER.writeValueAsString(event);
            for (WebSocketConnection conn : activeConnections) {
                conn.sendTextAndAwait(json);
            }
        } catch (Exception e) {
            LOG.error("Failed to broadcast device event over WebSocket", e);
        }
    }
    public void onThreatEvent(@Observes ThreatEvent threat) {
        LOG.info("Observing ThreatEvent: " + threat.description);
        try {
            var payload = new java.util.HashMap<String, String>();
            payload.put("type", "ALARM");
            payload.put("message", threat.description);
            payload.put("severity", threat.severity);
            String json = MAPPER.writeValueAsString(payload);
            for (WebSocketConnection conn : activeConnections) {
                conn.sendTextAndAwait(json);
            }
        } catch (Exception e) {
            LOG.error("Failed to broadcast threat event over WebSocket", e);
        }
    }
}
