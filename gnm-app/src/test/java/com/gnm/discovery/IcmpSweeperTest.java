package com.gnm.discovery;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class IcmpSweeperTest {

    @Inject
    IcmpSweeper icmpSweeper;

    @Test
    public void testCheckTargetReachableLocalhost() {
        // Localhost (127.0.0.1) should respond to reachability check
        boolean reachable = icmpSweeper.checkTargetReachable("127.0.0.1");
        assertTrue(reachable, "127.0.0.1 should be reachable");
    }

    @Test
    public void testCheckTargetReachableInvalidIp() {
        // Non-existent IP in private range should return false or not crash
        boolean reachable = icmpSweeper.checkTargetReachable("192.0.2.254");
        assertFalse(reachable);
    }
}
