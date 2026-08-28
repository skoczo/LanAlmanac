package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.Attributes;
import java.util.Hashtable;
import java.io.File;
import java.nio.file.Files;

@ApplicationScoped
public class JndiResolvConfProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(JndiResolvConfProbe.class);

    @Override
    public int getTimeoutMs() {
        return 400;
    }

    @Override
    public int getPriority() {
        return 51;
    }

    @Override
    public void execute(ProbeContext context) {
        if (context.getResolvedHostname() != null) return;
        String ipAddress = context.getIpAddress();
        try {
            File resolvConf = new File("/etc/resolv.conf");
            if (resolvConf.exists() && resolvConf.canRead()) {
                for (String line : Files.readAllLines(resolvConf.toPath())) {
                    line = line.trim();
                    if (line.startsWith("nameserver ")) {
                        String ns = line.substring("nameserver ".length()).trim();
                        if (ns.startsWith("127.") || ns.endsWith(".1")) continue;
                        String resolved = resolveViaJndi(ipAddress, ns);
                        if (resolved != null) {
                            context.setResolvedHostname(resolved);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {}
    }
    private String resolveViaJndi(String ipAddress, String dnsServer) {
        try {
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
        } catch (Exception e) {}
        return null;
    }
}
