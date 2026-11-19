package com.gnm.pkgmgmt.client;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DeviceLockManager {

    public record LockToken(UUID deviceId, UUID lockId, Instant expiresAt) {}

    private final ConcurrentHashMap<UUID, LockToken> activeLocks = new ConcurrentHashMap<>();

    public Optional<LockToken> tryAcquire(UUID deviceId, Duration ttl) {
        if (deviceId == null) {
            throw new IllegalArgumentException("deviceId is required");
        }
        Instant now = Instant.now();
        Duration effectiveTtl = (ttl == null || ttl.isNegative() || ttl.isZero())
                ? Duration.ofMinutes(15)
                : ttl;
        LockToken candidate = new LockToken(deviceId, UUID.randomUUID(), now.plus(effectiveTtl));

        LockToken existing =
                activeLocks.compute(
                        deviceId,
                        (id, current) -> {
                            if (current == null || current.expiresAt().isBefore(now)) {
                                return candidate;
                            }
                            return current;
                        });
        if (existing.lockId().equals(candidate.lockId())) {
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    public void release(LockToken token) {
        if (token == null) {
            return;
        }
        activeLocks.computeIfPresent(
                token.deviceId(),
                (id, current) -> current.lockId().equals(token.lockId()) ? null : current);
    }

    public boolean isLocked(UUID deviceId) {
        LockToken current = activeLocks.get(deviceId);
        if (current == null) {
            return false;
        }
        if (current.expiresAt().isBefore(Instant.now())) {
            activeLocks.remove(deviceId, current);
            return false;
        }
        return true;
    }

    public <T> T withDeviceLock(UUID deviceId, Duration ttl, Callable<T> action) {
        LockToken token =
                tryAcquire(deviceId, ttl)
                        .orElseThrow(
                                () ->
                                        new PkgWorkerException(
                                                "DEVICE_BUSY",
                                                "Another package operation is already running on this device",
                                                409));
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            release(token);
        }
    }

    public void withDeviceLock(UUID deviceId, Duration ttl, Runnable action) {
        withDeviceLock(
                deviceId,
                ttl,
                () -> {
                    action.run();
                    return null;
                });
    }
}

