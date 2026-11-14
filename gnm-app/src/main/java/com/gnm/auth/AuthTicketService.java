package com.gnm.auth;

import com.gnm.dto.AuthTicket;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AuthTicketService {

    private static final int TOKEN_BYTES = 32;
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, AuthTicket> tickets = new ConcurrentHashMap<>();

    public AuthTicket issue(String username, Set<String> roles, String purpose, String resourceId) {
        return issue(username, roles, purpose, resourceId, DEFAULT_TTL);
    }

    public AuthTicket issue(String username, Set<String> roles, String purpose, String resourceId, Duration ttl) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (purpose == null || !AuthTicket.VALID_PURPOSES.contains(purpose)) {
            throw new IllegalArgumentException("Invalid ticket purpose");
        }

        byte[] randomBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        String normalizedResource = (resourceId == null || resourceId.isBlank()) ? "" : resourceId.trim();
        Set<String> roleCopy = roles == null ? Set.of() : Set.copyOf(roles);
        Instant expiresAt = Instant.now().plus(ttl != null ? ttl : DEFAULT_TTL);

        AuthTicket ticket = new AuthTicket(token, username, roleCopy, purpose, normalizedResource, expiresAt);
        tickets.put(token, ticket);
        return ticket;
    }

    /**
     * Atomically consumes and validates a single-use ticket.
     */
    public Optional<AuthTicket> consume(String token, String expectedPurpose, String expectedResourceId) {
        if (token == null || token.isBlank() || expectedPurpose == null) {
            return Optional.empty();
        }

        AuthTicket ticket = tickets.remove(token);
        if (ticket == null) {
            return Optional.empty();
        }

        if (Instant.now().isAfter(ticket.expiresAt())) {
            return Optional.empty();
        }

        if (!expectedPurpose.equals(ticket.purpose())) {
            return Optional.empty();
        }

        String normalizedExpected = (expectedResourceId == null || expectedResourceId.isBlank())
                ? ""
                : expectedResourceId.trim();
        if (!Objects.equals(normalizedExpected, ticket.resourceId())) {
            return Optional.empty();
        }

        return Optional.of(ticket);
    }

    @Scheduled(every = "60s", identity = "auth-ticket-cleanup")
    public void purgeExpired() {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
    }

    int activeTicketCount() {
        return tickets.size();
    }
}
