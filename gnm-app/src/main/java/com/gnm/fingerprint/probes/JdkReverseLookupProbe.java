package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class JdkReverseLookupProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(JdkReverseLookupProbe.class);

    @Override
    public int getTimeoutMs() {
        return 2000;
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public void execute(ProbeContext context) {
        if (context.getResolvedHostname() != null) return;
        try {
            InetAddress addr = InetAddress.getByName(context.getIpAddress());
            String host = null;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                host = executor.submit(() -> addr.getCanonicalHostName()).get(2000, TimeUnit.MILLISECONDS);
            }
            if (host != null && !host.equals(context.getIpAddress()) && !host.isEmpty()) {
                context.setResolvedHostname(host);
            }
        } catch (Exception e) {}
    }
}
