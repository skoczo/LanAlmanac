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

        moduleManager.updateStatus("ebpf-passive-sniffer", Status.RUNNING, "Nasłuchiwanie eBPF aktywne");
        DiscoveryModuleStatus mod = moduleManager.getModuleStatus("ebpf-passive-sniffer");
        Assertions.assertNotNull(mod);
        Assertions.assertEquals(Status.RUNNING, mod.status);
        Assertions.assertEquals("Nasłuchiwanie eBPF aktywne", mod.currentActivity);

        moduleManager.updateError("ebpf-passive-sniffer", "Brak uprawnień CAP_BPF");
        mod = moduleManager.getModuleStatus("ebpf-passive-sniffer");
        Assertions.assertEquals(Status.ERROR, mod.status);
        Assertions.assertEquals("Brak uprawnień CAP_BPF", mod.errorMessage);
    }
}
