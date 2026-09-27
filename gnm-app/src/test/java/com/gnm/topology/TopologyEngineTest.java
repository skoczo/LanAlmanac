package com.gnm.topology;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@QuarkusTest
class TopologyEngineTest {

    @Inject
    TopologyEngine topologyEngine;

    @Test
    void testRunTopologyScanDoesNotThrow() {
        assertDoesNotThrow(() -> topologyEngine.runTopologyScan());
    }
}
