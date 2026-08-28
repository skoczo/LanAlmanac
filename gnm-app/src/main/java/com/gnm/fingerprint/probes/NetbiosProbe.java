package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;

@ApplicationScoped
public class NetbiosProbe implements NetworkProbe {
    private static final Logger LOG = Logger.getLogger(NetbiosProbe.class);

    @Override
    public int getTimeoutMs() {
        return 150;
    }

    @Override
    public int getPriority() {
        return 60;
    }

    @Override
    public void execute(ProbeContext context) {
        if (context.getResolvedHostname() != null) return;
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
        } catch (Exception e) {}
    }
}
