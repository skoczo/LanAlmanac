package com.gnm.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.InetAddress;

@ApplicationScoped
public class SubnetFilter {

    private static final Logger LOG = Logger.getLogger(SubnetFilter.class);

    @ConfigProperty(name = "gnm.subnet", defaultValue = "192.168.1.0/24")
    String subnetConfig;

    public String getSubnetConfig() {
        return subnetConfig != null ? subnetConfig.trim() : "";
    }

    public boolean isIpInSubnet(String ipStr) {
        if (ipStr == null || ipStr.isBlank() || "0.0.0.0".equals(ipStr) || "255.255.255.255".equals(ipStr)) {
            return false;
        }

        String config = getSubnetConfig();
        if (config.isBlank()) {
            return true; // Allow all if not configured
        }

        String[] allowedSubnets = config.split(",");
        for (String subnet : allowedSubnets) {
            if (matchesCidr(ipStr.trim(), subnet.trim())) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchesCidr(String ipStr, String cidrStr) {
        try {
            if (!cidrStr.contains("/")) {
                return ipStr.equalsIgnoreCase(cidrStr);
            }

            String[] parts = cidrStr.split("/");
            String targetIp = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            InetAddress ip = InetAddress.getByName(ipStr);
            InetAddress subnetIp = InetAddress.getByName(targetIp);

            byte[] ipBytes = ip.getAddress();
            byte[] subnetBytes = subnetIp.getAddress();

            if (ipBytes.length != subnetBytes.length) {
                return false;
            }

            int bits = prefixLength;
            for (int i = 0; i < ipBytes.length; i++) {
                if (bits >= 8) {
                    if (ipBytes[i] != subnetBytes[i]) return false;
                    bits -= 8;
                } else if (bits > 0) {
                    int mask = (0xFF << (8 - bits)) & 0xFF;
                    if ((ipBytes[i] & mask) != (subnetBytes[i] & mask)) return false;
                    bits = 0;
                } else {
                    break;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
