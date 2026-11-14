package com.gnm.dto;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable representation of a short-lived, single-use authentication ticket
 * used for SSE and WebSocket handshakes.
 */
public record AuthTicket(
        String token,
        String username,
        Set<String> roles,
        String purpose,
        String resourceId,
        Instant expiresAt) {

    public static final String PURPOSE_SSE_UPDATE = "sse-update";
    public static final String PURPOSE_WS_TERMINAL = "ws-terminal";
    public static final String PURPOSE_WS_EVENTS = "ws-events";

    public static final Set<String> VALID_PURPOSES = Set.of(
            PURPOSE_SSE_UPDATE,
            PURPOSE_WS_TERMINAL,
            PURPOSE_WS_EVENTS);
}
