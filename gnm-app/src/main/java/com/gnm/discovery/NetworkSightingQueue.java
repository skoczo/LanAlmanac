package com.gnm.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.LinkedBlockingQueue;
import com.gnm.model.NetworkSighting;

/**
 * Thread-safe FIFO queue that acts as the boundary between the <em>discovery producers</em>
 * (passive BPF sniffer, ICMP sweeper, DHCP listener, mDNS probe, etc.) and the
 * <em>processing consumer</em> ({@link com.gnm.fingerprint.FingerprintEngine}).
 *
 * <p>Each network packet or probe result that reveals a device on the network is wrapped
 * in a {@link com.gnm.model.NetworkSighting} and dropped here via {@link #offer}.
 * The FingerprintEngine drains the queue in its dedicated virtual-thread loop, performing
 * fingerprinting, deduplication, and device persistence — decoupled from the capture threads.
 *
 * <p>The internal queue is bounded to <strong>1 000 entries</strong> to prevent unbounded memory
 * growth during traffic bursts. Excess sightings are silently dropped by {@link #offer}
 * (non-blocking); this is acceptable because the FingerprintEngine debounces repeated
 * sightings for the same IP/MAC within a short window anyway.
 */
@ApplicationScoped
public class NetworkSightingQueue {


    private final LinkedBlockingQueue<NetworkSighting> queue = new LinkedBlockingQueue<>(1000);

    public boolean offer(NetworkSighting sighting) {
        return queue.offer(sighting);
    }

    public NetworkSighting take() throws InterruptedException {
        return queue.take();
    }

    public int size() {
        return queue.size();
    }

    public void clear() {
        queue.clear();
    }
}
