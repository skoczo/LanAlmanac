package com.gnm.resource;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.gnm.discovery.NetworkSightingQueue;
import com.gnm.fingerprint.FingerprintEngine;
import com.gnm.model.FingerprintVector;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.PhysicalDevice;

@Path("/api/diagnostics")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("gnm-admin")
public class DiagnosticsResource {

    @Inject
    NetworkSightingQueue sightingQueue;

    @Inject
    FingerprintEngine fingerprintEngine;

    @GET
    @Transactional
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> stats = new HashMap<>();

        // JVM Memory Stats
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
        long heapMax = memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024);
        long heapCommitted = memoryBean.getHeapMemoryUsage().getCommitted() / (1024 * 1024);
        long nonHeapUsed = memoryBean.getNonHeapMemoryUsage().getUsed() / (1024 * 1024);

        Map<String, Object> memory = new HashMap<>();
        memory.put("heapUsedMb", heapUsed);
        memory.put("heapMaxMb", heapMax);
        memory.put("heapCommittedMb", heapCommitted);
        memory.put("nonHeapUsedMb", nonHeapUsed);
        stats.put("memory", memory);

        // GC Stats
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long totalGcCount = 0;
        long totalGcTimeMs = 0;
        for (GarbageCollectorMXBean gcBean : gcBeans) {
            long count = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();
            if (count > 0) totalGcCount += count;
            if (time > 0) totalGcTimeMs += time;
        }
        Map<String, Object> gc = new HashMap<>();
        gc.put("totalCollections", totalGcCount);
        gc.put("totalTimeMs", totalGcTimeMs);
        stats.put("garbageCollection", gc);

        // Threads & CPU Stats
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        Map<String, Object> threads = new HashMap<>();
        threads.put("liveThreadCount", threadBean.getThreadCount());
        threads.put("peakThreadCount", threadBean.getPeakThreadCount());
        threads.put("daemonThreadCount", threadBean.getDaemonThreadCount());
        threads.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        stats.put("threadsAndCpu", threads);

        // Queue & Concurrency Stats
        Map<String, Object> queues = new HashMap<>();
        queues.put("sightingQueuePendingSize", sightingQueue.size());
        queues.put("activeScanPermitsAvailable", fingerprintEngine.getActiveScanPermitsAvailable());
        queues.put("processingPermitsAvailable", fingerprintEngine.getProcessingPermitsAvailable());
        stats.put("pipeline", queues);

        // Database Entity Stats
        Map<String, Object> db = new HashMap<>();
        db.put("physicalDevicesCount", PhysicalDevice.count());
        db.put("networkIdentitiesCount", NetworkIdentity.count());
        db.put("fingerprintVectorsCount", FingerprintVector.count());
        stats.put("database", db);

        // System Uptime
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        stats.put("uptimeSeconds", uptimeMs / 1000);

        return stats;
    }
}
