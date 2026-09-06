package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Standard Java SDK Reverse DNS Lookup Probe.
 * <p>
 * Uses {@link InetAddress#getCanonicalHostName()} to attempt reverse PTR resolution via system default DNS resolvers.
 * Scheduled with priority 100 as a general fallback when specialized JNDI, mDNS, or NetBIOS probes have not yet resolved a hostname.
 * Executes asynchronously within an executor task to enforce a strict timeout constraint on OS DNS lookups.
 */
@ApplicationScoped
public class JdkReverseLookupProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(JdkReverseLookupProbe.class);
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    @Override
    public int getTimeoutMs() {
        return 2000;
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public void execute(ProbeContext context) throws Exception {
        InetAddress addr = InetAddress.getByName(context.getIpAddress());

        // Submit reverse DNS lookup with a 2-second timeout to prevent blocking thread execution
        String host = EXECUTOR.submit(() -> addr.getCanonicalHostName()).get(2000, TimeUnit.MILLISECONDS);
        // Ensure returned host is valid and not just the raw IP string
        if (host != null && !host.equals(context.getIpAddress()) && !host.isEmpty()) {
            context.setResolvedHostname(host);
        }
    }

}

