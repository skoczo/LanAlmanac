package com.gnm.service.scanner;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import java.net.Socket;
import java.net.InetSocketAddress;

@ApplicationScoped
public class DefaultPortScannerEngine implements PortScannerEngine {
    private static final Logger LOG = Logger.getLogger(DefaultPortScannerEngine.class);
    private static final int TIMEOUT_MS = 2000;

    @Override
    public List<Integer> scanPorts(String ipAddress, List<Integer> portsToScan, int delayMs) {
        List<Integer> openPorts = new ArrayList<>();
        
        for (int port : portsToScan) {
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                
                try (Socket socket = new Socket()) {
                    // Try to connect. This blocks until success or timeout/error
                    socket.connect(new InetSocketAddress(ipAddress, port), TIMEOUT_MS);
                    openPorts.add(port);
                    LOG.debugf("Discovered open port %d on %s", port, ipAddress);
                } catch (Exception e) {
                    // Connection refused or timed out, port is closed or filtered
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warnf("Scan interrupted for %s", ipAddress);
                break;
            }
        }
        
        return openPorts;
    }
}
