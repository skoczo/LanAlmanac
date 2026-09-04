package com.gnm.service.scanner;

import com.gnm.dto.ScanProgress;
import com.gnm.model.FingerprintVector;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.PortScanState;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@ApplicationScoped
public class BackgroundScannerService {

    private static final Logger LOG = Logger.getLogger(BackgroundScannerService.class);
    
    // Limits concurrency to 2 active scans to prevent network flooding
    private static final int MAX_CONCURRENT_SCANS = 2;
    private final Semaphore scanSemaphore = new Semaphore(MAX_CONCURRENT_SCANS);
    
    private final BlockingQueue<UUID> scanQueue = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap<UUID, String> activeScans = new ConcurrentHashMap<>();
    private final AtomicInteger totalScannedCount = new AtomicInteger(0);

    @Inject
    PortScannerEngine scannerEngine;

    // A default list of top 100 common ports to scan. 
    private static final List<Integer> TOP_PORTS = List.of(
        21, 22, 23, 25, 53, 80, 110, 111, 135, 139, 143, 443, 445, 993, 995, 1723, 3306, 3389, 5900, 8080, 8443, 8000, 8006, 8123, 1883, 3000, 5000, 5001, 5555, 6053, 9090, 9443, 10000
    );

    @PostConstruct
    void init() {
        // Start the worker pool thread
        Thread coordinator = new Thread(this::processQueue);
        coordinator.setName("BackgroundScanner-Coordinator");
        coordinator.setDaemon(true);
        coordinator.start();
        LOG.info("BackgroundScannerService started with max concurrency " + MAX_CONCURRENT_SCANS);
    }

    @io.quarkus.scheduler.Scheduled(every = "60s", identity = "port-scan-enqueue-job")
    public void enqueuePendingDevices() {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST) return;
        
        // Enqueue any devices that are still PENDING and are ONLINE
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            java.util.List<PhysicalDevice> pending = PhysicalDevice.find("portScanState = ?1 and status = ?2", PortScanState.PENDING, com.gnm.model.enums.DeviceStatus.ONLINE).list();
            for (PhysicalDevice device : pending) {
                enqueueDevice(device.id);
            }
        });
    }

    public void enqueueDevice(UUID deviceId) {
        if (!scanQueue.contains(deviceId) && !activeScans.containsKey(deviceId)) {
            scanQueue.offer(deviceId);
        }
    }

    private void processQueue() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    UUID deviceId = scanQueue.take();
                    
                    // Acquire permit before starting a virtual thread to scan
                    scanSemaphore.acquire();
                    
                    executor.submit(() -> {
                        try {
                            scanDevice(deviceId);
                        } finally {
                            activeScans.remove(deviceId);
                            scanSemaphore.release();
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOG.error("Error submitting scan task", e);
                }
            }
        }
    }

    public ScanProgress getProgress() {
        ScanProgress progress = new ScanProgress();
        progress.queuedDevices = scanQueue.size();
        progress.activeScans = activeScans.size();
        progress.currentlyScanningIPs = new ArrayList<>(activeScans.values());
        progress.totalScanned = totalScannedCount.get();
        return progress;
    }

    public void resetForTest() {
        scanQueue.clear();
        activeScans.clear();
        totalScannedCount.set(0);
        // Drain semaphore permits just in case
        scanSemaphore.drainPermits();
        scanSemaphore.release(MAX_CONCURRENT_SCANS);
    }

    public void scanDevice(UUID deviceId) {
        String ipAddress = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
            PhysicalDevice device = PhysicalDevice.findById(deviceId);
            if (device == null) return null;
            
            if (device.status == com.gnm.model.enums.DeviceStatus.OFFLINE) {
                LOG.infof("Skipping port scan for device %s because it is OFFLINE", deviceId);
                device.portScanState = PortScanState.PENDING;
                device.persistAndFlush();
                return null;
            }
            
            String ip = null;
            org.hibernate.Hibernate.initialize(device.identities);
            if (!device.identities.isEmpty()) {
                ip = device.identities.iterator().next().ipAddress;
            }
            if (ip != null) {
                device.portScanState = PortScanState.SCAN_IN_PROGRESS;
                device.persistAndFlush();
            }
            return ip;
        });

        if (ipAddress == null || ipAddress.isBlank()) {
            LOG.warnf("Cannot scan device %s because no IP is associated or device is offline", deviceId);
            return;
        }

        LOG.infof("Starting low and slow port scan for device %s (IP: %s)", deviceId, ipAddress);
        activeScans.put(deviceId, ipAddress);
        
        // Use a 20ms delay between port attempts to be gentle on IoT stacks
        List<Integer> discoveredPorts = scannerEngine.scanPorts(ipAddress, TOP_PORTS, 20);
        
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            PhysicalDevice device = PhysicalDevice.findById(deviceId);
            if (device != null) {
                mergeOpenPorts(device, discoveredPorts);
                device.portScanState = PortScanState.FULLY_SCANNED;
                device.persist();
            }
        });
        
        totalScannedCount.incrementAndGet();
        LOG.infof("Finished scanning device %s. Found %d open ports", deviceId, discoveredPorts.size());
    }
    
    private void mergeOpenPorts(PhysicalDevice device, List<Integer> newlyDiscoveredPorts) {
        if (newlyDiscoveredPorts == null || newlyDiscoveredPorts.isEmpty()) {
            return;
        }
        
        // Find the latest fingerprint vector
        FingerprintVector latestVector = null;
        if (device.fingerprints != null && !device.fingerprints.isEmpty()) {
            latestVector = device.fingerprints.stream()
                    .max(java.util.Comparator.comparing(f -> f.capturedAt))
                    .orElse(null);
        }
        
        if (latestVector != null) {
            if (latestVector.openPorts == null) {
                latestVector.openPorts = new ArrayList<>();
            }
            java.util.Set<Integer> merged = new java.util.HashSet<>(latestVector.openPorts);
            merged.addAll(newlyDiscoveredPorts);
            latestVector.openPorts = new ArrayList<>(merged);
            latestVector.persist();
        }
    }
}
