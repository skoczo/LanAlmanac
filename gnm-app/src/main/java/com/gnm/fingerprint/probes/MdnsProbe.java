package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

@ApplicationScoped
public class MdnsProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(MdnsProbe.class);

    @Override
    public int getTimeoutMs() {
        return 500;
    }

    @Override
    public int getPriority() {
        return 70;
    }

    @Override
    public void execute(ProbeContext context) {
        if (context.getResolvedHostname() != null) return;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            String[] octets = context.getIpAddress().split("\\.");
            if (octets.length != 4) return;
            String arpa = octets[3] + "." + octets[2] + "." + octets[1] + "." + octets[0] + ".in-addr.arpa";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeShort(0x1234); dos.writeShort(0x0000); dos.writeShort(0x0001);
            dos.writeShort(0x0000); dos.writeShort(0x0000); dos.writeShort(0x0000);
            for (String part : arpa.split("\\.")) {
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
            Matcher m = Pattern.compile("([\\w-]{1,63})\\.local").matcher(payload);
            if (m.find()) {
                context.setResolvedHostname(m.group(1));
            }
        } catch (Exception e) {}
    }
}
