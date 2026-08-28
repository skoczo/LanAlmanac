package com.gnm.fingerprint;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.gnm.model.*;
import com.gnm.model.enums.*;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;

@ApplicationScoped
public class ActiveProber {
    private static final Logger LOG = Logger.getLogger(ActiveProber.class);
    private final java.util.concurrent.Semaphore activeScanConcurrency = new java.util.concurrent.Semaphore(5);
    public void acquirePermit() throws InterruptedException { activeScanConcurrency.acquire(); }
    public int getActiveScanPermitsAvailable() { return activeScanConcurrency.availablePermits(); }
    public void releasePermit() { activeScanConcurrency.release(); }


    public String resolveHostname(String ipAddress, List<Integer> openPorts) {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST && !Boolean.getBoolean("forceNetworkScan")) {
            return null; // Skip slow network lookups during regular tests
        }
        LOG.info("Starting hostname resolution check for IP: " + ipAddress);

        // Stage 1: Try local subnet gateway DNS (.1 address)
        int lastDot = ipAddress.lastIndexOf('.');
        if (lastDot > 0) {
            String subnetGateway = ipAddress.substring(0, lastDot) + ".1";
            LOG.info("[Stage 1] Querying subnet gateway DNS server " + subnetGateway + " for IP " + ipAddress);
            String resolved = resolveViaJndi(ipAddress, subnetGateway);
            if (resolved != null) {
                LOG.info("--> [Success Stage 1] Resolved hostname '" + resolved + "' for " + ipAddress + " via Subnet Gateway DNS (" + subnetGateway + ")");
                return resolved;
            }
        }

        // Stage 1.5: Try DNS servers from /etc/resolv.conf (e.g. a custom DHCP/DNS server like OpenWRT)
        try {
            java.io.File resolvConf = new java.io.File("/etc/resolv.conf");
            if (resolvConf.exists() && resolvConf.canRead()) {
                for (String line : java.nio.file.Files.readAllLines(resolvConf.toPath())) {
                    line = line.trim();
                    if (line.startsWith("nameserver ")) {
                        String ns = line.substring("nameserver ".length()).trim();
                        // Skip loopback (Docker embedded DNS) and the .1 gateway we already tried
                        if (ns.startsWith("127.") || ns.endsWith(".1")) continue;
                        LOG.info("[Stage 1.5] Querying resolv.conf DNS server " + ns + " for IP " + ipAddress);
                        String resolved = resolveViaJndi(ipAddress, ns);
                        if (resolved != null) {
                            LOG.info("--> [Success Stage 1.5] Resolved hostname '" + resolved + "' for " + ipAddress + " via resolv.conf DNS (" + ns + ")");
                            return resolved;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error reading /etc/resolv.conf during Stage 1.5 DNS resolution: " + e.getMessage());
        }
        
        // Stage 2: Try container default route gateway DNS
        String defaultGateway = getDefaultGateway();
        if (defaultGateway != null && !defaultGateway.equals(ipAddress)) {
            LOG.info("[Stage 2] Querying container default route gateway DNS server " + defaultGateway + " for IP " + ipAddress);
            String resolved = resolveViaJndi(ipAddress, defaultGateway);
            if (resolved != null) {
                LOG.info("--> [Success Stage 2] Resolved hostname '" + resolved + "' for " + ipAddress + " via Route Gateway DNS (" + defaultGateway + ")");
                return resolved;
            }
        }

        // Stage 3: Try NetBIOS Node Status unicast query (UDP port 137)
        LOG.info("[Stage 3] Querying NetBIOS unicast DNS for IP: " + ipAddress);
        String netbiosName = resolveViaNetbios(ipAddress);
        if (netbiosName != null) {
            LOG.info("--> [Success Stage 3] Resolved hostname '" + netbiosName + "' for " + ipAddress + " via NetBIOS Node Status");
            return netbiosName;
        }

        // Stage 4: Try mDNS Multicast Query (UDP port 5353)
        LOG.info("[Stage 4] Querying mDNS Multicast for IP: " + ipAddress);
        String mdnsName = resolveViaMdns(ipAddress);
        if (mdnsName != null) {
            LOG.info("--> [Success Stage 4] Resolved hostname '" + mdnsName + "' for " + ipAddress + " via mDNS Multicast");
            return mdnsName;
        }

        // Stage 4.5: Try UPnP SSDP Unicast (UDP port 1900)
        LOG.info("[Stage 4.5] Querying UPnP SSDP Unicast for IP: " + ipAddress);
        String upnpName = resolveViaUpnpUnicast(ipAddress);
        if (upnpName != null) {
            LOG.info("--> [Success Stage 4.5] Resolved hostname '" + upnpName + "' for " + ipAddress + " via UPnP SSDP");
            return upnpName;
        }

        // Stage 5: Try TLS Certificate Common Name (CN) extraction (Dynamic Open Ports + Fallback)
        LOG.info("[Stage 5] Querying TLS Certificate CN for IP: " + ipAddress);
        List<Integer> portsToTry = (openPorts != null && !openPorts.isEmpty()) 
            ? openPorts 
            : List.of(8006, 443, 8443);
        for (int port : portsToTry) {
            String cn = resolveViaTlsCert(ipAddress, port);
            if (cn != null) {
                LOG.info("--> [Success Stage 5] Resolved hostname '" + cn + "' for " + ipAddress + " via TLS Certificate CN on port " + port);
                return cn;
            }
        }

        // Stage 6: Try HTTP/HTTPS Title Scraping on common web ports
        LOG.info("[Stage 6] Querying HTTP Title for IP: " + ipAddress);
        List<Integer> httpPorts = List.of(80, 8080, 8000, 8123, 443, 8443, 8006);
        for (int port : httpPorts) {
            if (openPorts != null && !openPorts.isEmpty() && !openPorts.contains(port)) continue;
            boolean https = port == 443 || port == 8443 || port == 8006;
            String title = resolveViaHttpTitle(ipAddress, port, https);
            if (title != null && !title.equalsIgnoreCase("NetAlmanac") && !title.toLowerCase().contains("network manager")) {
                LOG.info("--> [Success Stage 6] Resolved hostname '" + title + "' for " + ipAddress + " via HTTP Title on port " + port);
                return title;
            }
        }

        // Stage 7: Standard JDK reverse lookup (slow, so done last)
        LOG.info("[Stage 7] Querying standard JDK reverse lookup for IP: " + ipAddress);
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(ipAddress);
            String host = addr.getCanonicalHostName();
            LOG.info("[Stage 7] JDK resolver returned " + host + " for IP " + ipAddress);
            if (host != null && !host.equals(ipAddress) && !host.isEmpty()) {
                LOG.info("--> [Success Stage 7] Resolved hostname '" + host + "' for " + ipAddress + " via JDK Reverse Lookup");
                return host;
            }
        } catch (Exception e) {
            // Ignore
        }

        // Stage 8: ESPHome Native API fallback
        if (openPorts != null && openPorts.contains(6053)) {
            LOG.info("--> [Success Stage 8] Resolved as ESPHome via port 6053 for " + ipAddress);
            return "ESPHome Device";
        }
        
        LOG.info("--> [Failed] Hostname resolution failed for IP: " + ipAddress);
        return null;
    }

    private String resolveViaTlsCert(String ipAddress, int port) {
        try {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[] {
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
            };

            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            
            javax.net.ssl.SSLSocketFactory factory = sc.getSocketFactory();
            try (javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) factory.createSocket()) {
                socket.connect(new java.net.InetSocketAddress(ipAddress, port), 250); // Fast timeout for local networks
                socket.setSoTimeout(250);
                
                socket.startHandshake();
                
                var certs = socket.getSession().getPeerCertificates();
                if (certs != null && certs.length > 0 && certs[0] instanceof java.security.cert.X509Certificate) {
                    java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) certs[0];
                    String dn = cert.getSubjectX500Principal().getName();
                    
                    // Parse CN from DN
                    for (String part : dn.split(",")) {
                        part = part.trim();
                        if (part.startsWith("CN=")) {
                            String cn = part.substring(3);
                            if (!cn.isEmpty() && !cn.contains(" ") && !cn.equalsIgnoreCase("localhost")) {
                                return cn;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Port closed, timeout, or SSL handshake failed
        }
        return null;
    }

    private String resolveViaNetbios(String ipAddress) {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.setSoTimeout(150); // Fast timeout for LAN unicast
            
            // 50-byte NetBIOS Node Status Query Packet
            byte[] query = new byte[50];
            query[0] = 0x00; query[1] = 0x01; // Transaction ID
            query[2] = 0x00; query[3] = 0x00; // Flags: Query
            query[4] = 0x00; query[5] = 0x01; // Questions: 1
            query[12] = 0x20;                 // Name Length (32 bytes)
            
            // Encoded "*" wildcard name space
            query[13] = 0x43; query[14] = 0x4b;
            for (int i = 15; i < 45; i++) {
                query[i] = 0x41;
            }
            query[45] = 0x00;                 // Terminator
            query[46] = 0x00; query[47] = 0x21; // Type: NBSTAT (33)
            query[48] = 0x00; query[49] = 0x01; // Class: IN (1)
            
            java.net.InetAddress addr = java.net.InetAddress.getByName(ipAddress);
            java.net.DatagramPacket request = new java.net.DatagramPacket(query, query.length, addr, 137);
            socket.send(request);
            
            byte[] responseBuffer = new byte[1024];
            java.net.DatagramPacket response = new java.net.DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            
            // Parse response bytes (minimal NetBIOS Node Status header + payload length is 57 bytes)
            if (response.getLength() >= 57) {
                int numNames = responseBuffer[56] & 0xFF;
                int offset = 57;
                for (int i = 0; i < numNames; i++) {
                    if (offset + 18 > response.getLength()) break;
                    
                    // Extract 15-byte ASCII NetBIOS name
                    StringBuilder nameBuilder = new StringBuilder();
                    for (int j = 0; j < 15; j++) {
                        char c = (char) responseBuffer[offset + j];
                        if (c > 31 && c < 127 && c != ' ') {
                            nameBuilder.append(c);
                        }
                    }
                    int type = responseBuffer[offset + 15] & 0xFF;
                    offset += 18;
                    
                    // NetBIOS unique name (Workstation Service type 0x00)
                    if (type == 0x00 && nameBuilder.length() > 0) {
                        return nameBuilder.toString().trim();
                    }
                }
            }
        } catch (Exception e) {
            // NetBIOS query timed out or target has no NetBIOS active
        }
        return null;
    }

    private String resolveViaMdns(String ipAddress) {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.setSoTimeout(500);

            // Construct an mDNS reverse DNS query (PTR for IP.in-addr.arpa)
            String[] octets = ipAddress.split("\\.");
            if (octets.length != 4) return null;
            String arpa = octets[3] + "." + octets[2] + "." + octets[1] + "." + octets[0] + ".in-addr.arpa";

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
            
            dos.writeShort(0x1234); // Transaction ID
            dos.writeShort(0x0000); // Flags: standard query
            dos.writeShort(0x0001); // Questions: 1
            dos.writeShort(0x0000); // Answer RRs: 0
            dos.writeShort(0x0000); // Authority RRs: 0
            dos.writeShort(0x0000); // Additional RRs: 0
            
            for (String part : arpa.split("\\.")) {
                dos.writeByte(part.length());
                dos.writeBytes(part);
            }
            dos.writeByte(0); // Root terminator
            dos.writeShort(0x000C); // Type: PTR
            dos.writeShort(0x0001); // Class: IN

            byte[] query = baos.toByteArray();
            java.net.InetAddress addr = java.net.InetAddress.getByName("224.0.0.251");
            java.net.DatagramPacket request = new java.net.DatagramPacket(query, query.length, addr, 5353);
            socket.send(request);

            byte[] responseBuffer = new byte[1024];
            java.net.DatagramPacket response = new java.net.DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);

            String payload = new String(responseBuffer, 0, response.getLength(), java.nio.charset.StandardCharsets.US_ASCII);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([\\w-]{1,63})\\.local").matcher(payload);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception e) {
            // Ignore timeout
        }
        return null;
    }

    private String resolveViaUpnpUnicast(String ipAddress) {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.setSoTimeout(500);

            String query = "M-SEARCH * HTTP/1.1\r\n" +
                           "Host: 239.255.255.250:1900\r\n" +
                           "Man: \"ssdp:discover\"\r\n" +
                           "ST: ssdp:all\r\n" +
                           "MX: 1\r\n\r\n";

            byte[] requestBytes = query.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.net.InetAddress addr = java.net.InetAddress.getByName(ipAddress);
            java.net.DatagramPacket request = new java.net.DatagramPacket(requestBytes, requestBytes.length, addr, 1900);
            socket.send(request);

            byte[] responseBuffer = new byte[2048];
            java.net.DatagramPacket response = new java.net.DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);

            String responseString = new String(responseBuffer, 0, response.getLength(), java.nio.charset.StandardCharsets.UTF_8);
            
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)Server:\\s*(.*?)\r\n").matcher(responseString);
            if (m.find()) {
                String server = m.group(1).trim();
                if (!server.isEmpty() && !server.equalsIgnoreCase("UPnP/1.0")) {
                    return server.split(" ")[0].trim();
                }
            }
            
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("(?i)USN:\\s*(.*?)\r\n").matcher(responseString);
            if (m2.find()) {
                String usn = m2.group(1).trim();
                return usn.length() > 30 ? usn.substring(0, 30) : usn;
            }
        } catch (Exception e) {
            // Ignore timeout
        }
        return null;
    }

    public String fetchUpnpUsn(String ipAddress) {
        try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
            socket.setSoTimeout(500);

            String query = "M-SEARCH * HTTP/1.1\r\n" +
                           "Host: 239.255.255.250:1900\r\n" +
                           "Man: \"ssdp:discover\"\r\n" +
                           "ST: ssdp:all\r\n" +
                           "MX: 1\r\n\r\n";

            byte[] requestBytes = query.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.net.InetAddress addr = java.net.InetAddress.getByName(ipAddress);
            java.net.DatagramPacket request = new java.net.DatagramPacket(requestBytes, requestBytes.length, addr, 1900);
            socket.send(request);

            byte[] responseBuffer = new byte[2048];
            java.net.DatagramPacket response = new java.net.DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);

            String responseString = new String(responseBuffer, 0, response.getLength(), java.nio.charset.StandardCharsets.UTF_8);
            
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)USN:\\s*(.*?)\r\n").matcher(responseString);
            if (m.find()) {
                String usn = m.group(1).trim();
                return usn;
            }
        } catch (Exception e) {
            // Ignore timeout
        }
        return null;
    }

    private String resolveViaHttpTitle(String ipAddress, int port, boolean https) {
        try {
            String protocol = https ? "https" : "http";
            java.net.URL url = new java.net.URI(protocol + "://" + ipAddress + ":" + port + "/").toURL();
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            
            // Bypass SSL verification for HTTPS IoT devices
            if (https && conn instanceof javax.net.ssl.HttpsURLConnection) {
                javax.net.ssl.HttpsURLConnection httpsConn = (javax.net.ssl.HttpsURLConnection) conn;
                javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[] {
                    new javax.net.ssl.X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    }
                };
                javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                sc.init(null, trustAllCerts, new java.security.SecureRandom());
                httpsConn.setSSLSocketFactory(sc.getSocketFactory());
                httpsConn.setHostnameVerifier((hostname, session) -> true);
            }

            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "GNM-Scanner/1.0");
            
            conn.connect();
            String serverHeader = conn.getHeaderField("Server");
            int code = conn.getResponseCode();

            if (code == 200 || code == 401 || code == 403 || code == 301 || code == 302 || code == 307 || code == 308) {
                java.io.InputStream stream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                if (stream != null) {
                    try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(stream))) {
                        String inputLine;
                        StringBuilder content = new StringBuilder();
                        int bytesRead = 0;
                        while ((inputLine = in.readLine()) != null && bytesRead < 16384) {
                            content.append(inputLine).append("\n");
                            bytesRead += inputLine.length();
                        }
                        
                        String fullContent = content.toString();
                        
                        // JSON fallback for Tasmota/Shelly/ESPHome web servers
                        if (fullContent.trim().startsWith("{")) {
                            java.util.regex.Matcher jm = java.util.regex.Pattern.compile("\"(?:hostname|name|device_name|id)\"\\s*:\\s*\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(fullContent);
                            if (jm.find()) {
                                return jm.group(1).trim();
                            }
                        }
                        
                        // DOTALL regex to catch <title> tags spanning multiple lines
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<title.*?>\\s*(.*?)\\s*</title>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(fullContent);
                        if (m.find()) {
                            String title = m.group(1).trim();
                            if (!title.isEmpty() && !title.equalsIgnoreCase("Document") && !title.equalsIgnoreCase("Index") && !title.equalsIgnoreCase("Web Server")) {
                                return title;
                            }
                        }
                    }
                }
            }
            
            // Fallback: If no valid title was found but a descriptive Server header exists
            if (serverHeader != null && !serverHeader.isEmpty() && !serverHeader.equalsIgnoreCase("nginx") && !serverHeader.toLowerCase().contains("lighttpd")) {
                return serverHeader.split(" ")[0].trim();
            }
        } catch (Exception e) {
            // Port closed or not HTTP
        }
        return null;
    }

    private String resolveViaJndi(String ipAddress, String dnsServer) {
        try {
            java.util.Hashtable<String, String> env = new java.util.Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns://" + dnsServer);
            env.put("com.sun.jndi.dns.timeout.initial", "400"); // 400ms timeout
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            
            javax.naming.directory.DirContext ctx = new javax.naming.directory.InitialDirContext(env);
            
            String[] parts = ipAddress.split("\\.");
            if (parts.length == 4) {
                String reverseIp = parts[3] + "." + parts[2] + "." + parts[1] + "." + parts[0] + ".in-addr.arpa";
                javax.naming.directory.Attributes attrs = ctx.getAttributes(reverseIp, new String[] { "PTR" });
                var attribute = attrs.get("PTR");
                if (attribute != null) {
                    String val = attribute.get().toString();
                    if (val.endsWith(".")) {
                        val = val.substring(0, val.length() - 1);
                    }
                    return val;
                }
            }
        } catch (Exception e) {
            LOG.info("  | JNDI query to " + dnsServer + " for IP " + ipAddress + " failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
        return null;
    }

    private String getDefaultGateway() {
        try {
            java.io.File file = new java.io.File("/proc/net/route");
            if (file.exists() && file.canRead()) {
                java.util.List<String> lines = java.nio.file.Files.readAllLines(file.toPath());
                for (int i = 1; i < lines.size(); i++) {
                    String[] parts = lines.get(i).trim().split("\\s+");
                    if (parts.length >= 3) {
                        String dest = parts[1];
                        String gatewayHex = parts[2];
                        if ("00000000".equals(dest) && !"00000000".equals(gatewayHex)) {
                            long val = Long.parseLong(gatewayHex, 16);
                            return ((val & 0xFF)) + "." +
                                   ((val >> 8) & 0xFF) + "." +
                                   ((val >> 16) & 0xFF) + "." +
                                   ((val >> 24) & 0xFF);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.debug("Error reading /proc/net/route for default gateway: " + e.getMessage());
        }
        return null;
    }

    public void syncNetworkServices(PhysicalDevice device, List<Integer> openPorts) {
        if (openPorts == null || openPorts.isEmpty()) return;

        List<NetworkService> existingServices = NetworkService.list("physicalDevice.id", device.id);
        List<Integer> existingPorts = existingServices.stream().map(s -> s.port).toList();

        for (Integer port : openPorts) {
            if (!existingPorts.contains(port)) {
                NetworkService ns = new NetworkService();
                ns.physicalDevice = device;
                ns.port = port;
                ns.protocol = "TCP";
                ns.manageable = true;
                ns.discovered = true;
                ns.firstSeen = Instant.now();
                ns.lastSeen = Instant.now();
                
                if (port == 22 || port == 2222 || port == 2223 || port == 2224) {
                    ns.serviceType = "SSH";
                    ns.label = "SSH Service";
                } else if (port == 80 || port == 8080 || port == 9000 || port == 8123) {
                    ns.serviceType = "HTTP";
                    ns.label = "Web UI";
                } else if (port == 443 || port == 8443 || port == 8006) {
                    ns.serviceType = "HTTPS";
                    ns.label = "Secure Web UI";
                } else if (port == 161) {
                    ns.serviceType = "SNMP";
                    ns.label = "SNMP Agent";
                    ns.protocol = "UDP";
                } else {
                    ns.serviceType = "UNKNOWN";
                    ns.label = "Discovered Port " + port;
                    ns.manageable = false;
                }
                
                ns.persist();
                LOG.info("Auto-created NetworkService for port " + port + " on device " + device.id);
            } else {
                existingServices.stream().filter(s -> s.port.equals(port)).findFirst().ifPresent(ns -> {
                    ns.lastSeen = Instant.now();
                    ns.persist();
                });
            }
        }
    }

    public List<Integer> scanOpenPorts(String ipAddress) {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST && !Boolean.getBoolean("forceNetworkScan")) {
            return new ArrayList<>(); // Skip slow port scanning during regular tests
        }
        int[] portsToScan = { 22, 80, 443, 1883, 3000, 5000, 5001, 5555, 6053, 8000, 8008, 8080, 8090, 8006, 8123, 8443, 9090, 9443, 10000 };
        List<Integer> openPorts = new ArrayList<>();
        
        try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
            for (int port : portsToScan) {
                futures.add(executor.submit(() -> {
                    try (java.net.Socket socket = new java.net.Socket()) {
                        socket.connect(new java.net.InetSocketAddress(ipAddress, port), 2000); // 2000ms timeout
                        return port;
                    } catch (Exception e) {
                        return null;
                    }
                }));
            }
            
            for (var future : futures) {
                try {
                    Integer p = future.get();
                    if (p != null) {
                        openPorts.add(p);
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
        return openPorts;
    }

    public String fetchSshHostKey(String ip, int port) {
        AtomicReference<String> hostKeyRef = new AtomicReference<>();
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
                String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey);
                hostKeyRef.set(fingerprint);
                return false; // Reject key to immediately abort handshake
            });
            client.start();
            try (ClientSession session = client.connect("fakeuser", ip, port).verify(15000).getSession()) {
                session.auth().verify(15000); 
            } catch (Exception e) {
                // Expected to fail because we reject the server key, or auth fails
            }
        } catch (Exception e) {
            LOG.error("Failed to fetch SSH host key from " + ip + ":" + port, e);
        }
        String key = hostKeyRef.get();
        LOG.info("fetchSshHostKey(" + ip + ", " + port + ") returned: " + key);
        return key;
    }
}
