package com.gnm.discovery;

import com.gnm.model.SettingChangedEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@QuarkusTest
class PassivePacketListenerTest {

    @Inject
    PassivePacketListener listener;

    @Test
    void testStartStopAndSettingChanged() {
        assertDoesNotThrow(() -> listener.startCapture());
        assertDoesNotThrow(() -> listener.onSettingChanged(new SettingChangedEvent("gnm.listen.interface", "eth0")));
        assertDoesNotThrow(() -> listener.stop());
    }
}
