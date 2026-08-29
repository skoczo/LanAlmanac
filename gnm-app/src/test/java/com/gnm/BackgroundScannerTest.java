package com.gnm;

import com.gnm.dto.ScanProgress;
import com.gnm.model.FingerprintVector;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.ManagementState;
import com.gnm.model.enums.PortScanState;
import com.gnm.service.scanner.BackgroundScannerService;
import com.gnm.service.scanner.PortScannerEngine;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
public class BackgroundScannerTest {

    @Inject
    BackgroundScannerService scannerService;

    @InjectMock
    PortScannerEngine scannerEngineMock;

    @BeforeEach
    @Transactional
    public void setup() {
        scannerService.resetForTest();
        com.gnm.model.NetworkIdentity.deleteAll();
        com.gnm.model.FingerprintVector.deleteAll();
        PhysicalDevice.deleteAll();
    }

    @AfterEach
    @Transactional
    public void teardown() {
        scannerService.resetForTest();
        com.gnm.model.NetworkIdentity.deleteAll();
        com.gnm.model.FingerprintVector.deleteAll();
        PhysicalDevice.deleteAll();
    }

    @Test
    public void testStateTransitionsAndMerge() throws InterruptedException {
        // Given: A device with some previous ports
        UUID deviceId = createMockDevice("192.168.1.50", List.of(80));

        // Mock the scanner to return 443 and 8080 after a short delay
        when(scannerEngineMock.scanPorts(Mockito.eq("192.168.1.50"), any(), anyInt()))
                .thenAnswer(invocation -> {
                    Thread.sleep(100);
                    return List.of(443, 8080);
                });

        // Verify initial state
        PhysicalDevice initial = PhysicalDevice.findById(deviceId);
        assertEquals(PortScanState.PENDING, initial.portScanState);

        // When: We enqueue and process the device
        scannerService.enqueueDevice(deviceId);
        
        // Let the background thread pick it up and commit the SCAN_IN_PROGRESS transaction
        Thread.sleep(100); 
        
        PhysicalDevice inProgress = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
            PhysicalDevice.getEntityManager().clear();
            return PhysicalDevice.findById(deviceId);
        });
        assertEquals(PortScanState.SCAN_IN_PROGRESS, inProgress.portScanState);
        
        // Wait for scan to finish
        for (int i = 0; i < 20; i++) {
            if (scannerService.getProgress().activeScans == 0) {
                break;
            }
            Thread.sleep(100);
        }

        // Then: Device is fully scanned and ports are merged (80 + 443 + 8080)
        PhysicalDevice finished = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
            PhysicalDevice.getEntityManager().clear();
            PhysicalDevice d = PhysicalDevice.findById(deviceId);
            org.hibernate.Hibernate.initialize(d.fingerprints);
            return d;
        });
        assertEquals(PortScanState.FULLY_SCANNED, finished.portScanState);
        
        FingerprintVector vector = finished.fingerprints.iterator().next();
        assertNotNull(vector.openPorts);
        assertTrue(vector.openPorts.containsAll(List.of(80, 443, 8080)));
        assertEquals(3, vector.openPorts.size());
    }

    @Test
    public void testConcurrencyLimitAndProgressReporting() throws InterruptedException {
        // Given: 5 devices
        UUID d1 = createMockDevice("192.168.2.1", List.of());
        UUID d2 = createMockDevice("192.168.2.2", List.of());
        UUID d3 = createMockDevice("192.168.2.3", List.of());
        UUID d4 = createMockDevice("192.168.2.4", List.of());
        UUID d5 = createMockDevice("192.168.2.5", List.of());

        AtomicInteger concurrentScans = new AtomicInteger(0);
        AtomicInteger maxConcurrentScans = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(5);

        // Mock the scanner to take 2000ms and track concurrency
        when(scannerEngineMock.scanPorts(anyString(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    int current = concurrentScans.incrementAndGet();
                    if (current > maxConcurrentScans.get()) {
                        maxConcurrentScans.set(current);
                    }
                    Thread.sleep(2000); // 2 seconds so we can definitely catch it
                    concurrentScans.decrementAndGet();
                    latch.countDown();
                    return List.of(22);
                });

        // When: We enqueue all 5
        scannerService.enqueueDevice(d1);
        scannerService.enqueueDevice(d2);
        scannerService.enqueueDevice(d3);
        scannerService.enqueueDevice(d4);
        scannerService.enqueueDevice(d5);

        // Wait for workers to pick up tasks and start scanning
        for (int i = 0; i < 20; i++) {
            if (scannerService.getProgress().activeScans == 2) {
                break;
            }
            Thread.sleep(100);
        }
        
        ScanProgress progress = scannerService.getProgress();
        
        // Then: Concurrency should be exactly 2 (MAX_CONCURRENT_SCANS limit)
        assertTrue(progress.activeScans <= 2, "Active scans should be limited to 2");
        assertTrue(progress.activeScans > 0, "Should have active scans");
        
        // Wait for all to finish scanning (could take 2s * 3 batches = 6s)
        boolean finishedInTime = latch.await(10, TimeUnit.SECONDS);
        assertTrue(finishedInTime, "Scans did not finish within 10 seconds");
        
        // Wait for activeScans to clear completely
        for (int i = 0; i < 20; i++) {
            if (scannerService.getProgress().activeScans == 0) {
                break;
            }
            Thread.sleep(100);
        }

        assertEquals(2, maxConcurrentScans.get(), "Concurrency should never exceed 2");
        
        ScanProgress finalProgress = scannerService.getProgress();
        assertEquals(0, finalProgress.activeScans);
        assertEquals(0, finalProgress.queuedDevices);
    }

    @Transactional
    protected UUID createMockDevice(String ip, List<Integer> initialPorts) {
        PhysicalDevice device = new PhysicalDevice();
        device.displayName = "Mock " + ip;
        device.status = DeviceStatus.ONLINE;
        device.firstSeen = Instant.now();
        device.lastSeen = Instant.now();
        device.managementState = ManagementState.DISCOVERED;
        device.portScanState = PortScanState.PENDING;
        device.persistAndFlush();

        NetworkIdentity id = new NetworkIdentity();
        id.ipAddress = ip;
        id.macAddress = "AA:BB:CC:DD:EE:FF";
        id.physicalDevice = device;
        id.firstSeen = Instant.now();
        id.lastSeen = Instant.now();
        id.persistAndFlush();
        device.identities.add(id);
        
        long count = com.gnm.model.NetworkIdentity.count();
        System.out.println("NetworkIdentities in DB: " + count);

        FingerprintVector fp = new FingerprintVector();
        fp.capturedAt = Instant.now();
        fp.physicalDevice = device;
        if (!initialPorts.isEmpty()) {
            fp.openPorts = initialPorts;
        }
        fp.persistAndFlush();
        device.fingerprints.add(fp);

        return device.id;
    }
}
