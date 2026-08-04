package com.gnm.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.websockets.next.*;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gnm.fingerprint.FingerprintEngine.DeviceEvent;

@WebSocket(path = "/ws/events")
@ApplicationScoped
public class EventWebSocket {

    private static final Logger LOG = Logger.getLogger(EventWebSocket.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    OpenConnections connections;

    @OnOpen
    public void onOpen(WebSocketConnection conn) {
        LOG.info("WebSocket connection opened: " + conn.id());
    }

    @OnClose
    public void onClose(WebSocketConnection conn) {
        LOG.info("WebSocket connection closed: " + conn.id());
    }

    public void onDeviceEvent(@Observes DeviceEvent event) {
        LOG.info("Observing DeviceEvent: " + event.type + " for device: " + event.displayName);
        try {
            String json = MAPPER.writeValueAsString(event);
            for (WebSocketConnection conn : connections) {
                conn.sendTextAndAwait(json);
            }
        } catch (Exception e) {
            LOG.error("Failed to broadcast device event over WebSocket", e);
        }
    }
}
