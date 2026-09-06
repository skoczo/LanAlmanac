package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.Attributes;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JNDI Reverse DNS Probe targeting the Subnet Gateway (`x.y.z.1`).
 * <p>
 * Derives the assumed local router/gateway IP address by changing the target IP's last octet to `.1` (e.g. `192.168.1.1`).
 * Issues a direct JNDI reverse PTR DNS lookup to this router address, which often acts as the local DHCP/DNS authority.
 * Priority 50 (runs first among JNDI probes).
 */
@ApplicationScoped
public class JndiSubnetGatewayProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(JndiSubnetGatewayProbe.class);
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    @Override
    public int getTimeoutMs() {
        return 400;
    }

    @Override
    public int getPriority() {
        return 50;
    }

    @Override
    public void execute(ProbeContext context) throws Exception {
        String ipAddress = context.getIpAddress();

        int lastDot = ipAddress.lastIndexOf('.');
        if (lastDot > 0) {
            // Infer the subnet router gateway IP address assuming standard /24 network structure (.1)
            String subnetGateway = ipAddress.substring(0, lastDot) + ".1";
            LOG.info("[Stage 1] Querying subnet gateway DNS server " + subnetGateway + " for IP " + ipAddress);
            // Execute direct JNDI PTR query to the .1 router address
            String resolved = EXECUTOR.submit(() -> resolveViaJndi(ipAddress, subnetGateway)).get(400, TimeUnit.MILLISECONDS);
            if (resolved != null) {
                context.setResolvedHostname(resolved);
            }
        }
    }

    /**
     * Performs direct JNDI reverse PTR lookup against the specified local DNS server IP.
     */
    private String resolveViaJndi(String ipAddress, String dnsServer) throws Exception {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("java.naming.provider.url", "dns://" + dnsServer);
        env.put("com.sun.jndi.dns.timeout.initial", "400");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        DirContext ctx = new InitialDirContext(env);
        String[] parts = ipAddress.split("\\.");
        if (parts.length == 4) {
            String reverseIp = parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0] + ".in-addr.arpa";
            Attributes attrs = ctx.getAttributes(reverseIp, new String[] { "PTR" });
            var attribute = attrs.get("PTR");
            if (attribute != null) {
                String val = attribute.get().toString();
                if (val.endsWith(".")) val = val.substring(0, val.length() - 1);
                return val;
            }
        }
        return null;
    }

}

