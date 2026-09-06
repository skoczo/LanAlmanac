package com.gnm.fingerprint.probes;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;

/**
 * NetBIOS Name Service (NBNS) UDP Probe.
 * <p>
 * Transmits a raw NetBIOS Node Status Request packet over UDP to port 137 of the target IP address.
 * Parses the NetBIOS response to extract the NetBIOS Workstation/Computer Name (type 0x00).
 * Useful for identifying Windows hosts, Windows Server instances, and Samba file servers.
 * Priority 60.
 */
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
    public void execute(ProbeContext context) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {

            socket.setSoTimeout(150);
            
            // Build raw NetBIOS NBSTAT (Node Status) query packet (50 bytes)
            byte[] query = new byte[50];
            query[0] = 0x00; query[1] = 0x01; // Transaction ID: 0x0001
            query[2] = 0x00; query[3] = 0x00; // Flags: Query, Broadcast
            query[4] = 0x00; query[5] = 0x01; // Questions: 1
            query[12] = 0x20;                 // Encoded name length (32 bytes)
            query[13] = 0x43; query[14] = 0x4b; // First byte of wildcard '*' encoded in NetBIOS format (CKAAAA...)
            for (int i = 15; i < 45; i++) query[i] = 0x41; // Pad remaining encoded name bytes ('A')
            query[45] = 0x00;                 // Terminator byte
            query[46] = 0x00; query[47] = 0x21; // Type: NBSTAT (0x0021)
            query[48] = 0x00; query[49] = 0x01; // Class: IN (0x0001)
            
            InetAddress addr = InetAddress.getByName(context.getIpAddress());
            DatagramPacket request = new DatagramPacket(query, query.length, addr, 137);
            socket.send(request);
            
            byte[] responseBuffer = new byte[1024];
            DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(response);
            
            // Parse response header and body to extract NetBIOS names
            if (response.getLength() >= 57) {
                int numNames = responseBuffer[56] & 0xFF; // Number of names returned
                int offset = 57;
                for (int i = 0; i < numNames; i++) {
                    if (offset + 18 > response.getLength()) break;
                    StringBuilder nameBuilder = new StringBuilder();
                    // Read 15-character ASCII NetBIOS name field
                    for (int j = 0; j < 15; j++) {
                        char c = (char) responseBuffer[offset + j];
                        if (c > 31 && c < 127 && c != ' ') nameBuilder.append(c);
                    }
                    int type = responseBuffer[offset + 15] & 0xFF;
                    offset += 18;
                    // Type 0x00 indicates a Workstation/Machine Name record
                    if (type == 0x00 && nameBuilder.length() > 0) {
                        context.setResolvedHostname(nameBuilder.toString().trim());
                        return;
                    }
                }
            }
        }
    }

}

