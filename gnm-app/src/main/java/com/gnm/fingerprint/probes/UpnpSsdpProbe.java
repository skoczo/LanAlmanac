package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UPnP SSDP Server Header Hostname Probe.
 * <p>
 * Sends an SSDP {@code M-SEARCH} UDP query to port 1900 of the target IP address.
 * Parses the {@code Server:} HTTP response header (e.g. {@code Server: Linux/3.x UPnP/1.0 DLNADOC/1.50 Realtek/1.0})
 * to extract device software/hostname metadata if available.
 * Priority 75.
 */
@ApplicationScoped
public class UpnpSsdpProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(UpnpSsdpProbe.class);

    @Override
    public int getTimeoutMs() {
        return 500;
    }

    @Override
    public int getPriority() {
        return 75;
    }

    @Override
    public void execute(ProbeContext context) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {

            socket.setSoTimeout(500);
            // Format unicast SSDP M-SEARCH discovery query
            String query = "M-SEARCH * HTTP/1.1\r\nHost: 239.255.255.250:1900\r\nMan: \"ssdp:discover\"\r\nST: ssdp:all\r\nMX: 1\r\n\r\n";
            byte[] requestBytes = query.getBytes(StandardCharsets.UTF_8);
            InetAddress addr = InetAddress.getByName(context.getIpAddress());
            DatagramPacket request = new DatagramPacket(requestBytes, requestBytes.length, addr, 1900);
            socket.send(request);
            
            byte[] responseBuffer = new byte[2048];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            
            String responseString = new String(responseBuffer, 0, response.getLength(), StandardCharsets.UTF_8);
            // Match the 'Server:' header line in the HTTP response
            Matcher m = Pattern.compile("(?i)Server:\\s*(.*?)\\r\\n").matcher(responseString);
            if (m.find()) {
                String server = m.group(1).trim();
                // Filter out generic UPnP version tags and extract leading token
                if (!server.isEmpty() && !server.equalsIgnoreCase("UPnP/1.0")) {
                    context.setResolvedHostname(server.split(" ")[0].trim());
                }
            }
        }
    }

}

