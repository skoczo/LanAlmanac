package com.gnm.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gnm.dto.AuthTicket;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthTicketServiceTest {

    private AuthTicketService ticketService;

    @BeforeEach
    void setUp() {
        ticketService = new AuthTicketService();
    }

    @Test
    void testIssueAndConsumeSuccess() {
        AuthTicket issued = ticketService.issue(
                "alice",
                Set.of("gnm-admin"),
                AuthTicket.PURPOSE_WS_TERMINAL,
                "device-123");

        assertNotNull(issued.token());
        assertTrue(issued.token().length() >= 40);

        Optional<AuthTicket> consumed = ticketService.consume(
                issued.token(),
                AuthTicket.PURPOSE_WS_TERMINAL,
                "device-123");

        assertTrue(consumed.isPresent());
        assertEquals("alice", consumed.get().username());
        assertTrue(consumed.get().roles().contains("gnm-admin"));
        assertEquals("device-123", consumed.get().resourceId());
    }

    @Test
    void testDoubleConsumeRejected() {
        AuthTicket issued = ticketService.issue(
                "alice",
                Set.of("gnm-admin"),
                AuthTicket.PURPOSE_SSE_UPDATE,
                "device-123");

        assertTrue(ticketService.consume(issued.token(), AuthTicket.PURPOSE_SSE_UPDATE, "device-123").isPresent());
        assertFalse(ticketService.consume(issued.token(), AuthTicket.PURPOSE_SSE_UPDATE, "device-123").isPresent());
    }

    @Test
    void testWrongPurposeRejectedAndConsumed() {
        AuthTicket issued = ticketService.issue(
                "alice",
                Set.of("gnm-admin"),
                AuthTicket.PURPOSE_WS_EVENTS,
                null);

        assertFalse(ticketService.consume(issued.token(), AuthTicket.PURPOSE_WS_TERMINAL, null).isPresent());
        // Even after a mismatched attempt, the single-use ticket must be burned
        assertFalse(ticketService.consume(issued.token(), AuthTicket.PURPOSE_WS_EVENTS, null).isPresent());
    }

    @Test
    void testWrongResourceIdRejectedAndConsumed() {
        AuthTicket issued = ticketService.issue(
                "alice",
                Set.of("gnm-operator"),
                AuthTicket.PURPOSE_WS_TERMINAL,
                "device-aaa");

        assertFalse(ticketService.consume(issued.token(), AuthTicket.PURPOSE_WS_TERMINAL, "device-bbb").isPresent());
        assertFalse(ticketService.consume(issued.token(), AuthTicket.PURPOSE_WS_TERMINAL, "device-aaa").isPresent());
    }

    @Test
    void testExpiredTicketRejected() {
        AuthTicket issued = ticketService.issue(
                "alice",
                Set.of("gnm-admin"),
                AuthTicket.PURPOSE_SSE_UPDATE,
                "device-123",
                Duration.ofMillis(-10));

        assertFalse(ticketService.consume(issued.token(), AuthTicket.PURPOSE_SSE_UPDATE, "device-123").isPresent());
    }

    @Test
    void testPurgeExpiredRemovesStaleTickets() {
        ticketService.issue(
                "alice",
                Set.of("gnm-admin"),
                AuthTicket.PURPOSE_WS_EVENTS,
                null,
                Duration.ofMillis(-50));
        ticketService.issue(
                "bob",
                Set.of("gnm-viewer"),
                AuthTicket.PURPOSE_WS_EVENTS,
                null,
                Duration.ofSeconds(30));

        assertEquals(2, ticketService.activeTicketCount());
        ticketService.purgeExpired();
        assertEquals(1, ticketService.activeTicketCount());
    }

    @Test
    void testInvalidArgumentsThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                ticketService.issue("", Set.of("gnm-admin"), AuthTicket.PURPOSE_WS_EVENTS, null));
        assertThrows(IllegalArgumentException.class, () ->
                ticketService.issue("alice", Set.of("gnm-admin"), "unknown-purpose", null));
    }
}
