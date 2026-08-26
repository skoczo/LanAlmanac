package com.gnm.topology;

import com.gnm.model.Credential;
import com.gnm.model.NetworkLink;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceType;
import com.gnm.model.enums.DiscoveryProtocol;
import com.gnm.model.enums.ManagementState;
import com.gnm.service.VaultEngine;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.TransportMapping;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TopologyEngine {

    private static final Logger LOG = Logger.getLogger(TopologyEngine.class);

    @Inject
    VaultEngine vaultEngine;

    // Standard LLDP MIB for Remote System Name: lldpRemSysName
    private static final OID LLDP_REM_SYS_NAME = new OID("1.0.8802.1.1.2.1.4.1.1.9");

    @Scheduled(every = "5m", identity = "topology-scan-job")
    @Transactional
    public void runTopologyScan() {
        if (io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.TEST) {
            return;
        }
        LOG.info("Starting scheduled Topology Scan (LLDP/CDP/ARP)");

        if (!vaultEngine.isUnsealed()) {
            LOG.warn("Vault is sealed! Cannot fetch SNMP credentials for topology mapping.");
            return;
        }

        List<PhysicalDevice> managedDevices = PhysicalDevice.list("managementState", ManagementState.MANAGED);
        
        for (PhysicalDevice device : managedDevices) {
            // Only query switches and routers for LLDP data
            if (device.deviceType == DeviceType.SWITCH || device.deviceType == DeviceType.ROUTER) {
                pollDeviceTopology(device);
            }
        }
    }

    private void pollDeviceTopology(PhysicalDevice device) {
        // Find SNMP credential for this device
        Optional<Credential> snmpCredOpt = device.credentials.stream()
                .filter(c -> c.credentialType == com.gnm.model.enums.CredentialType.SNMP)
                .findFirst();

        if (snmpCredOpt.isEmpty()) {
            return;
        }

        Credential cred = snmpCredOpt.get();
        byte[] decryptedBytes = vaultEngine.decrypt(cred.encryptedPayload, cred.noncePayload);
        String communityString = new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
        if (communityString == null) return;

        // Try to find the active IP address for this device
        String ipAddress = null;
        if (device.identities != null && !device.identities.isEmpty()) {
            ipAddress = device.identities.iterator().next().ipAddress;
        }

        if (ipAddress == null) return;

        LOG.info("Polling topology data for " + device.displayName + " at " + ipAddress);

        try {
            TransportMapping<? extends Address> transport = new DefaultUdpTransportMapping();
            Snmp snmp = new Snmp(transport);
            transport.listen();

            Address targetAddress = GenericAddress.parse("udp:" + ipAddress + "/161");
            CommunityTarget<Address> target = new CommunityTarget<>();
            target.setCommunity(new OctetString(communityString));
            target.setAddress(targetAddress);
            target.setRetries(2);
            target.setTimeout(1500);
            target.setVersion(SnmpConstants.version2c);

            // Simple GETNEXT for LLDP Remote System Name as a proof-of-concept
            PDU pdu = new PDU();
            pdu.add(new VariableBinding(LLDP_REM_SYS_NAME));
            pdu.setType(PDU.GETNEXT);

            ResponseEvent<Address> event = snmp.send(pdu, target, null);

            if (event != null && event.getResponse() != null) {
                PDU response = event.getResponse();
                if (response.getErrorStatus() == PDU.noError) {
                    for (VariableBinding vb : response.getVariableBindings()) {
                        if (vb.getOid().startsWith(LLDP_REM_SYS_NAME)) {
                            String remoteSysName = vb.getVariable().toString();
                            LOG.info("Discovered LLDP Neighbor on " + device.displayName + ": " + remoteSysName);
                            
                            // Link resolution logic goes here (find PhysicalDevice with matching hostname and create NetworkLink)
                            // This is left as a stub for the topology mapping engine
                        }
                    }
                }
            }
            snmp.close();
        } catch (Exception e) {
            LOG.error("Failed SNMP poll for device " + device.displayName, e);
        }
    }
}
