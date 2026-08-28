package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class UpnpUsnProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(UpnpUsnProbe.class);

    @Override
    public int getTimeoutMs() {
        return 500;
    }

    @Override
    public int getPriority() {
        return 20;
    }

    @Override
    public void execute(ProbeContext context) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            String query = "M-SEARCH * HTTP/1.1\r\nHost: 239.255.255.250:1900\r\nMan: \"ssdp:discover\"\r\nST: ssdp:all\r\nMX: 1\r\n\r\n";
            byte[] requestBytes = query.getBytes(StandardCharsets.UTF_8);
            InetAddress addr = InetAddress.getByName(context.getIpAddress());
            DatagramPacket request = new DatagramPacket(requestBytes, requestBytes.length, addr, 1900);
            socket.send(request);
            byte[] responseBuffer = new byte[2048];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            String responseString = new String(responseBuffer, 0, response.getLength(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("(?i)USN:\\s*(.*?)\\r\\n").matcher(responseString);
            if (m.find()) {
                String usn = m.group(1).trim();
                context.getCandidate().ssdpUsn = usn;
            }
        } catch (Exception e) {}
    }
}
