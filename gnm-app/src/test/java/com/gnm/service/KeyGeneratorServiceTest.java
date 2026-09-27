package com.gnm.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@QuarkusTest
class KeyGeneratorServiceTest {

    @Inject
    KeyGeneratorService keyGeneratorService;

    @Test
    void testGenerateKeysIfNeeded() {
        assertDoesNotThrow(() -> keyGeneratorService.generateKeysIfNeeded());
    }
}
