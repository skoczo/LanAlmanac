import os

probe_dir = "/home/skoczo/workspace/GreatNetworkManager/gnm-app/src/main/java/com/gnm/fingerprint/probes"

def write_probe(name, priority, timeout, imports, body):
    path = os.path.join(probe_dir, f"{name}.java")
    content = f"""package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
{imports}

@ApplicationScoped
public class {name} implements NetworkProbe {{
    private static final Logger LOG = Logger.getLogger({name}.class);

    @Override
    public int getTimeoutMs() {{
        return {timeout};
    }}

    @Override
    public int getPriority() {{
        return {priority};
    }}

    @Override
    public void execute(ProbeContext context) {{
{body}
    }}
}}
"""
    with open(path, "w") as f:
        f.write(content)

write_probe("PortScannerProbe", 10, 1000, 
    "import java.util.ArrayList;\nimport java.util.List;\nimport java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;\nimport java.util.concurrent.Future;\nimport java.net.Socket;\nimport java.net.InetSocketAddress;",
    """        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST && !Boolean.getBoolean("forceNetworkScan")) {
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
                    Integer p = future.get();
                    if (p != null) openPorts.add(p);
                } catch (Exception e) {}
            }
        }
        context.setOpenPorts(openPorts);
        context.getCandidate().openPorts = openPorts;"""
)

write_probe("SshHostKeyProbe", 20, 2000,
    "import org.apache.sshd.client.SshClient;\nimport org.apache.sshd.client.session.ClientSession;\nimport org.apache.sshd.common.config.keys.KeyUtils;\nimport org.apache.sshd.common.digest.BuiltinDigests;\nimport java.util.concurrent.atomic.AtomicReference;",
    """        if (context.getOpenPorts().isEmpty()) return;
        for (Integer port : context.getOpenPorts()) {
            if (port == 22 || port == 2222 || port == 2223 || port == 2224) {
                AtomicReference<String> hostKeyRef = new AtomicReference<>();
                try (SshClient client = SshClient.setUpDefaultClient()) {
                    client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
                        String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
                        hostKeyRef.set(fingerprint);
                        return false;
                    });
                    client.start();
                    try (ClientSession session = client.connect("fakeuser", context.getIpAddress(), port).verify(2000).getSession()) {
                        session.auth().verify(2000); 
                    } catch (Exception e) {}
                } catch (Exception e) {
                    LOG.error("Failed to fetch SSH host key", e);
                }
                String key = hostKeyRef.get();
                if (key != null && !key.isEmpty()) {
                    context.getCandidate().sshHostKeys.add(key);
                }
            }
        }"""
)

write_probe("UpnpUsnProbe", 20, 500,
    "import java.net.DatagramSocket;\nimport java.net.DatagramPacket;\nimport java.net.InetAddress;\nimport java.nio.charset.StandardCharsets;\nimport java.util.regex.Matcher;\nimport java.util.regex.Pattern;",
    """        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            String query = "M-SEARCH * HTTP/1.1\\r\\nHost: 239.255.255.250:1900\\r\\nMan: \\"ssdp:discover\\"\\r\\nST: ssdp:all\\r\\nMX: 1\\r\\n\\r\\n";
            byte[] requestBytes = query.getBytes(StandardCharsets.UTF_8);
            InetAddress addr = InetAddress.getByName(context.getIpAddress());
            DatagramPacket request = new DatagramPacket(requestBytes, requestBytes.length, addr, 1900);
            socket.send(request);
            byte[] responseBuffer = new byte[2048];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            String responseString = new String(responseBuffer, 0, response.getLength(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("(?i)USN:\\\\s*(.*?)\\\\r\\\\n").matcher(responseString);
            if (m.find()) {
                String usn = m.group(1).trim();
                context.getCandidate().ssdpUsn = usn;
            }
        } catch (Exception e) {}"""
)

write_probe("JndiSubnetGatewayProbe", 50, 400,
    "import javax.naming.directory.DirContext;\nimport javax.naming.directory.InitialDirContext;\nimport javax.naming.directory.Attributes;\nimport java.util.Hashtable;",
    """        if (context.getResolvedHostname() != null) return;
        String ipAddress = context.getIpAddress();
        int lastDot = ipAddress.lastIndexOf('.');
        if (lastDot > 0) {
            String subnetGateway = ipAddress.substring(0, lastDot) + ".1";
            LOG.info("[Stage 1] Querying subnet gateway DNS server " + subnetGateway + " for IP " + ipAddress);
            String resolved = resolveViaJndi(ipAddress, subnetGateway);
            if (resolved != null) {
                context.setResolvedHostname(resolved);
            }
        }
    }
    private String resolveViaJndi(String ipAddress, String dnsServer) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns://" + dnsServer);
            env.put("com.sun.jndi.dns.timeout.initial", "400");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            DirContext ctx = new InitialDirContext(env);
            String[] parts = ipAddress.split("\\\\.");
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
        return null;"""
)

write_probe("JndiResolvConfProbe", 51, 400,
    "import javax.naming.directory.DirContext;\nimport javax.naming.directory.InitialDirContext;\nimport javax.naming.directory.Attributes;\nimport java.util.Hashtable;\nimport java.io.File;\nimport java.nio.file.Files;",
    """        if (context.getResolvedHostname() != null) return;
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
            String[] parts = ipAddress.split("\\\\.");
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
        return null;"""
)

write_probe("JndiDefaultGatewayProbe", 52, 400,
    "import javax.naming.directory.DirContext;\nimport javax.naming.directory.InitialDirContext;\nimport javax.naming.directory.Attributes;\nimport java.util.Hashtable;\nimport java.io.BufferedReader;\nimport java.io.InputStreamReader;",
    """        if (context.getResolvedHostname() != null) return;
        String ipAddress = context.getIpAddress();
        String defaultGateway = getDefaultGateway();
        if (defaultGateway != null && !defaultGateway.equals(ipAddress)) {
            String resolved = resolveViaJndi(ipAddress, defaultGateway);
            if (resolved != null) {
                context.setResolvedHostname(resolved);
            }
        }
    }
    private String getDefaultGateway() {
        try {
            Process process = Runtime.getRuntime().exec("ip route");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("default via ")) {
                        return line.split(" ")[2].trim();
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }
    private String resolveViaJndi(String ipAddress, String dnsServer) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns://" + dnsServer);
            env.put("com.sun.jndi.dns.timeout.initial", "400");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            DirContext ctx = new InitialDirContext(env);
            String[] parts = ipAddress.split("\\\\.");
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
        return null;"""
)

write_probe("NetbiosProbe", 60, 150,
    "import java.net.DatagramSocket;\nimport java.net.DatagramPacket;\nimport java.net.InetAddress;",
    """        if (context.getResolvedHostname() != null) return;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(150);
            byte[] query = new byte[50];
            query[0] = 0x00; query[1] = 0x01;
            query[2] = 0x00; query[3] = 0x00;
            query[4] = 0x00; query[5] = 0x01;
            query[12] = 0x20;
            query[13] = 0x43; query[14] = 0x4b;
            for (int i = 15; i < 45; i++) query[i] = 0x41;
            query[45] = 0x00; query[46] = 0x00; query[47] = 0x21;
            query[48] = 0x00; query[49] = 0x01;
            
            InetAddress addr = InetAddress.getByName(context.getIpAddress());
            DatagramPacket request = new DatagramPacket(query, query.length, addr, 137);
            socket.send(request);
            
            byte[] responseBuffer = new byte[1024];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            
            if (response.getLength() >= 57) {
                int numNames = responseBuffer[56] & 0xFF;
                int offset = 57;
                for (int i = 0; i < numNames; i++) {
                    if (offset + 18 > response.getLength()) break;
                    StringBuilder nameBuilder = new StringBuilder();
                    for (int j = 0; j < 15; j++) {
                        char c = (char) responseBuffer[offset + j];
                        if (c > 31 && c < 127 && c != ' ') nameBuilder.append(c);
                    }
                    int type = responseBuffer[offset + 15] & 0xFF;
                    offset += 18;
                    if (type == 0x00 && nameBuilder.length() > 0) {
                        context.setResolvedHostname(nameBuilder.toString().trim());
                        return;
                    }
                }
            }
        } catch (Exception e) {}"""
)

write_probe("MdnsProbe", 70, 500,
    "import java.net.DatagramSocket;\nimport java.net.DatagramPacket;\nimport java.net.InetAddress;\nimport java.nio.charset.StandardCharsets;\nimport java.util.regex.Matcher;\nimport java.util.regex.Pattern;\nimport java.io.ByteArrayOutputStream;\nimport java.io.DataOutputStream;",
    """        if (context.getResolvedHostname() != null) return;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            String[] octets = context.getIpAddress().split("\\\\.");
            if (octets.length != 4) return;
            String arpa = octets[3] + "." + octets[2] + "." + octets[1] + "." + octets[0] + ".in-addr.arpa";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeShort(0x1234); dos.writeShort(0x0000); dos.writeShort(0x0001);
            dos.writeShort(0x0000); dos.writeShort(0x0000); dos.writeShort(0x0000);
            for (String part : arpa.split("\\\\.")) {
                dos.writeByte(part.length()); dos.writeBytes(part);
            }
            dos.writeByte(0); dos.writeShort(0x000C); dos.writeShort(0x0001);
            byte[] query = baos.toByteArray();
            InetAddress addr = InetAddress.getByName("224.0.0.251");
            DatagramPacket request = new DatagramPacket(query, query.length, addr, 5353);
            socket.send(request);
            
            byte[] responseBuffer = new byte[1024];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            
            String payload = new String(responseBuffer, 0, response.getLength(), StandardCharsets.US_ASCII);
            Matcher m = Pattern.compile("([\\\\w-]{1,63})\\\\.local").matcher(payload);
            if (m.find()) {
                context.setResolvedHostname(m.group(1));
            }
        } catch (Exception e) {}"""
)

write_probe("UpnpSsdpProbe", 75, 500,
    "import java.net.DatagramSocket;\nimport java.net.DatagramPacket;\nimport java.net.InetAddress;\nimport java.nio.charset.StandardCharsets;\nimport java.util.regex.Matcher;\nimport java.util.regex.Pattern;",
    """        if (context.getResolvedHostname() != null) return;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            String query = "M-SEARCH * HTTP/1.1\\r\\nHost: 239.255.255.250:1900\\r\\nMan: \\"ssdp:discover\\"\\r\\nST: ssdp:all\\r\\nMX: 1\\r\\n\\r\\n";
            byte[] requestBytes = query.getBytes(StandardCharsets.UTF_8);
            InetAddress addr = InetAddress.getByName(context.getIpAddress());
            DatagramPacket request = new DatagramPacket(requestBytes, requestBytes.length, addr, 1900);
            socket.send(request);
            
            byte[] responseBuffer = new byte[2048];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            
            String responseString = new String(responseBuffer, 0, response.getLength(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("(?i)Server:\\\\s*(.*?)\\\\r\\\\n").matcher(responseString);
            if (m.find()) {
                String server = m.group(1).trim();
                if (!server.isEmpty() && !server.equalsIgnoreCase("UPnP/1.0")) {
                    context.setResolvedHostname(server.split(" ")[0].trim());
                }
            }
        } catch (Exception e) {}"""
)

write_probe("TlsCertProbe", 80, 250*3,
    "import javax.net.ssl.SSLContext;\nimport javax.net.ssl.SSLSocket;\nimport javax.net.ssl.SSLSocketFactory;\nimport javax.net.ssl.TrustManager;\nimport javax.net.ssl.X509TrustManager;\nimport java.security.cert.X509Certificate;\nimport java.security.SecureRandom;\nimport java.net.InetSocketAddress;\nimport java.util.List;",
    """        if (context.getResolvedHostname() != null) return;
        List<Integer> portsToTry = context.getOpenPorts().isEmpty() ? List.of(8006, 443, 8443) : context.getOpenPorts();
        for (int port : portsToTry) {
            try {
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
                    socket.connect(new InetSocketAddress(context.getIpAddress(), port), 250);
                    socket.setSoTimeout(250);
                    socket.startHandshake();
                    var certs = socket.getSession().getPeerCertificates();
                    if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
                        X509Certificate cert = (X509Certificate) certs[0];
                        String dn = cert.getSubjectX500Principal().getName();
                        for (String part : dn.split(",")) {
                            part = part.trim();
                            if (part.startsWith("CN=")) {
                                String cn = part.substring(3);
                                if (!cn.isEmpty() && !cn.contains(" ") && !cn.equalsIgnoreCase("localhost")) {
                                    context.setResolvedHostname(cn);
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {}
        }"""
)

write_probe("HttpTitleProbe", 90, 500*7,
    "import java.net.URL;\nimport java.net.URI;\nimport java.net.HttpURLConnection;\nimport javax.net.ssl.HttpsURLConnection;\nimport javax.net.ssl.SSLContext;\nimport javax.net.ssl.TrustManager;\nimport javax.net.ssl.X509TrustManager;\nimport java.security.cert.X509Certificate;\nimport java.security.SecureRandom;\nimport java.util.List;\nimport java.io.BufferedReader;\nimport java.io.InputStreamReader;\nimport java.util.regex.Matcher;\nimport java.util.regex.Pattern;",
    """        if (context.getResolvedHostname() != null) return;
        List<Integer> httpPorts = List.of(80, 8080, 8000, 8123, 443, 8443, 8006);
        for (int port : httpPorts) {
            if (!context.getOpenPorts().isEmpty() && !context.getOpenPorts().contains(port)) continue;
            boolean https = port == 443 || port == 8443 || port == 8006;
            try {
                String protocol = https ? "https" : "http";
                URL url = new URI(protocol + "://" + context.getIpAddress() + ":" + port + "/").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                if (https && conn instanceof HttpsURLConnection) {
                    HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                    TrustManager[] trustAllCerts = new TrustManager[] {
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return null; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                    };
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, trustAllCerts, new SecureRandom());
                    httpsConn.setSSLSocketFactory(sc.getSocketFactory());
                    httpsConn.setHostnameVerifier((hostname, session) -> true);
                }
                conn.setConnectTimeout(500);
                conn.setReadTimeout(500);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "GNM-Scanner/1.0");
                conn.connect();
                int code = conn.getResponseCode();
                if (code == 200 || code == 401 || code == 403) {
                    StringBuilder html = new StringBuilder();
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String inputLine;
                        while ((inputLine = in.readLine()) != null) {
                            html.append(inputLine);
                            if (html.length() > 8192) break;
                        }
                    } catch (Exception e) {
                        try (BufferedReader err = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                            String inputLine;
                            while ((inputLine = err.readLine()) != null) {
                                html.append(inputLine);
                                if (html.length() > 8192) break;
                            }
                        } catch (Exception ignored) {}
                    }
                    Matcher m = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html.toString());
                    if (m.find()) {
                        String title = m.group(1).trim();
                        if (!title.isEmpty() && !title.equalsIgnoreCase("LanAlmanac") && !title.toLowerCase().contains("network manager")) {
                            context.setResolvedHostname(title);
                            return;
                        }
                    }
                }
            } catch (Exception e) {}
        }"""
)

write_probe("JdkReverseLookupProbe", 100, 2000,
    "import java.net.InetAddress;\nimport java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;\nimport java.util.concurrent.TimeUnit;",
    """        if (context.getResolvedHostname() != null) return;
        try {
            InetAddress addr = InetAddress.getByName(context.getIpAddress());
            String host = null;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                host = executor.submit(() -> addr.getCanonicalHostName()).get(2000, TimeUnit.MILLISECONDS);
            }
            if (host != null && !host.equals(context.getIpAddress()) && !host.isEmpty()) {
                context.setResolvedHostname(host);
            }
        } catch (Exception e) {}"""
)
