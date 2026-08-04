package com.gnm.discovery;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.LinkedBlockingQueue;
import com.gnm.model.NetworkSighting;

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
