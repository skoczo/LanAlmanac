package com.gnm.service;

import com.gnm.fingerprint.FingerprintEngine;
import com.gnm.model.DeviceStatusHistory;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import java.time.Instant;
import java.util.UUID;

@ApplicationScoped
public class DeviceStatusHistoryRecorder {
    private static final Logger LOG = Logger.getLogger(DeviceStatusHistoryRecorder.class);

    @Transactional
    public void onDeviceEvent(@ObservesAsync FingerprintEngine.DeviceEvent event) {
        // Only log status transitions
        if ("STATUS_CHANGE".equals(event.type) || "ONLINE".equals(event.type)) {
            try {
                UUID deviceId = UUID.fromString(event.deviceId);
                PhysicalDevice device = PhysicalDevice.findById(deviceId);
                
                if (device != null) {
                    DeviceStatusHistory history = new DeviceStatusHistory();
                    history.physicalDevice = device;
                    history.status = "ONLINE".equals(event.status) ? DeviceStatus.ONLINE : DeviceStatus.OFFLINE;
                    history.ipAddress = event.ipAddress;
                    history.timestamp = Instant.now();
                    history.persist();
                    
                    LOG.debugf("Recorded status history for device %s: %s", device.displayName, history.status);
                }
            } catch (Exception e) {
                LOG.error("Failed to record device status history", e);
            }
        }
    }
}
