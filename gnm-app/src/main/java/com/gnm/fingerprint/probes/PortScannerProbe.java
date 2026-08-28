package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.net.Socket;
import java.net.InetSocketAddress;

@ApplicationScoped
public class PortScannerProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(PortScannerProbe.class);

    @Override
    public int getTimeoutMs() {
        return 1000;
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public void execute(ProbeContext context) {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST && !Boolean.getBoolean("forceNetworkScan")) {
            return;
        }
        int[] portsToScan = { 22, 80, 443, 1883, 3000, 5000, 5001, 5555, 6053, 8000, 8008, 8080, 8090, 8006, 8123, 8443, 9090, 9443, 10000 };
        List<Integer> openPorts = new ArrayList<>();
        
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int port : portsToScan) {
                futures.add(executor.submit(() -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(context.getIpAddress(), port), 1000);
                        return port;
                    } catch (Exception e) {
                        return null;
                    }
                }));
            }
            
            for (var future : futures) {
                try {
                    Integer p = future.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (p != null) openPorts.add(p);
                } catch (Exception e) {}
            }
        }
        context.setOpenPorts(openPorts);
        context.getCandidate().openPorts = openPorts;
    }
}
