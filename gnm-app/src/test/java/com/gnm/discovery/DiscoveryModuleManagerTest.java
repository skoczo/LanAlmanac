package com.gnm.discovery;

import com.gnm.discovery.model.DiscoveryModuleStatus;
import com.gnm.discovery.model.DiscoveryModuleStatus.Status;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

@QuarkusTest
public class DiscoveryModuleManagerTest {

    @Inject
    DiscoveryModuleManager moduleManager;

    @Test
    public void testModuleRegistrationAndStatusUpdates() {
        List<DiscoveryModuleStatus> modules = moduleManager.getAllModuleStatuses();
        Assertions.assertTrue(modules.size() >= 3);

        moduleManager.updateStatus("passive-sniffer", Status.RUNNING, "Nasłuchiwanie pasywne aktywne");
        DiscoveryModuleStatus mod = moduleManager.getModuleStatus("passive-sniffer");
        Assertions.assertNotNull(mod);
        Assertions.assertEquals(Status.RUNNING, mod.status);
        Assertions.assertEquals("Nasłuchiwanie pasywne aktywne", mod.currentActivity);

        moduleManager.updateError("passive-sniffer", "Brak uprawnień");
        mod = moduleManager.getModuleStatus("passive-sniffer");
        Assertions.assertEquals(Status.ERROR, mod.status);
        Assertions.assertEquals("Brak uprawnień", mod.errorMessage);
    }
}
