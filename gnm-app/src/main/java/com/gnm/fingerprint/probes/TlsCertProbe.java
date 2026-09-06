package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.security.SecureRandom;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * SSL/TLS X.509 Certificate Common Name (CN) Probe.
 * <p>
 * Establishes a raw SSL/TLS socket connection to known HTTPS service ports (e.g. 443, 8443, 8006 Proxmox VE).
 * Configures a trust-all X509TrustManager to inspect self-signed certificates.
 * Extracts the Subject X.500 Distinguished Name (DN) Common Name (CN=) to determine device/service hostname.
 * Priority 80.
 */
@ApplicationScoped
public class TlsCertProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(TlsCertProbe.class);

    @Override
    public int getTimeoutMs() {
        return 750;
    }

    @Override
    public int getPriority() {
        return 80;
    }

    @Override
    public void execute(ProbeContext context) throws Exception {
        // Determine ports to attempt based on open port discovery results

        List<Integer> portsToTry = context.getOpenPorts().isEmpty() ? List.of(8006, 443, 8443) : context.getOpenPorts();
        for (int port : portsToTry) {
            // Trust manager that accepts self-signed network device certificates
            TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new SecureRandom());
            SSLSocketFactory factory = sc.getSocketFactory();
            
            try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
                // Connect with short 250ms socket timeout
                socket.connect(new InetSocketAddress(context.getIpAddress(), port), 250);
                socket.setSoTimeout(250);
                socket.startHandshake();
                
                var certs = socket.getSession().getPeerCertificates();
                if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
                    X509Certificate cert = (X509Certificate) certs[0];
                    String dn = cert.getSubjectX500Principal().getName();
                    // Parse Distinguished Name (DN) for CN= value
                    for (String part : dn.split(",")) {
                        part = part.trim();
                        if (part.startsWith("CN=")) {
                            String cn = part.substring(3);
                            // Ensure CN is non-empty, contains no spaces, and isn't generic localhost
                            if (!cn.isEmpty() && !cn.contains(" ") && !cn.equalsIgnoreCase("localhost")) {
                                context.setResolvedHostname(cn);
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

}

