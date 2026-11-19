package com.gnm.pkgmgmt.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceLockManagerTest {

    @Test
    void preventsConcurrentLocksForSameDeviceAndAllowsDifferentDevices() {
        DeviceLockManager lockManager = new DeviceLockManager();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();

        var tokenA1 = lockManager.tryAcquire(deviceA, Duration.ofMinutes(5));
        assertTrue(tokenA1.isPresent(), "first lock on deviceA must succeed");

        var tokenA2 = lockManager.tryAcquire(deviceA, Duration.ofMinutes(5));
        assertFalse(tokenA2.isPresent(), "second concurrent lock on deviceA must be rejected");

        var tokenB1 = lockManager.tryAcquire(deviceB, Duration.ofMinutes(5));
        assertTrue(tokenB1.isPresent(), "lock on deviceB must succeed while deviceA is locked");

        lockManager.release(tokenA1.get());
        var tokenA3 = lockManager.tryAcquire(deviceA, Duration.ofMinutes(5));
        assertTrue(tokenA3.isPresent(), "lock on deviceA must succeed after release");

        lockManager.release(tokenA3.get());
        lockManager.release(tokenB1.get());
    }

    @Test
    void withDeviceLockReleasesOnException() {
        DeviceLockManager lockManager = new DeviceLockManager();
        UUID deviceId = UUID.randomUUID();

        assertThrows(
                RuntimeException.class,
                () ->
                        lockManager.withDeviceLock(
                                deviceId,
                                Duration.ofMinutes(1),
                                () -> {
                                    throw new RuntimeException("boom");
                                }));

        assertFalse(lockManager.isLocked(deviceId), "lock must be released in finally after exception");
    }
}

