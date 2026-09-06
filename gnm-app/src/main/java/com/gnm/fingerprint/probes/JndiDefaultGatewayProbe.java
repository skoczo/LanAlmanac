package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.Attributes;
import java.util.Hashtable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JNDI Reverse DNS Probe targeting the Default Gateway DNS service.
 * <p>
 * Extracts the default network gateway IP address by reading the system routing table ({@code ip route}).
 * Performs a direct reverse PTR DNS lookup via Java Naming and Directory Interface (JNDI) DNS context
 * directly targeted at the gateway. This bypasses local OS cache issues and queries the primary local router.
 * Priority 52.
 */
@ApplicationScoped
public class JndiDefaultGatewayProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(JndiDefaultGatewayProbe.class);
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    @Override
    public int getTimeoutMs() {
        return 400;
    }

    @Override
    public int getPriority() {
        return 52;
    }

    @Override
    public void execute(ProbeContext context) throws Exception {
        String ipAddress = context.getIpAddress();

        // Determine system default gateway IP address
        String defaultGateway = getDefaultGateway();
        if (defaultGateway != null && !defaultGateway.equals(ipAddress)) {
            // Perform JNDI PTR resolution against default gateway with 400ms timeout
            String resolved = EXECUTOR.submit(() -> resolveViaJndi(ipAddress, defaultGateway)).get(400, TimeUnit.MILLISECONDS);
            if (resolved != null) {
                context.setResolvedHostname(resolved);
            }
        }
    }

    /**
     * Executes 'ip route' command to discover default gateway IP address.
     */
    private String getDefaultGateway() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"ip", "route"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("default via ")) {
                        return line.split(" ")[2].trim();
                    }
                }
            }
        } catch (Exception e) {
            // Return null if 'ip' utility is not installed or accessible on host OS
        }
        return null;
    }


    /**
     * Performs a direct DNS reverse PTR lookup for target IP via JNDI against specified DNS server.
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

