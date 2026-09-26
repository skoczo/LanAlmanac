package com.gnm.service;

import com.gnm.fingerprint.FingerprintEngine;
import com.gnm.model.DeviceStatusHistory;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class DeviceStatusHistoryRecorder {
    private static final Logger LOG = Logger.getLogger(DeviceStatusHistoryRecorder.class);

    @Inject
    @ConfigProperty(name = "gnm.history.max-records-per-device", defaultValue = "50")
    int maxRecordsPerDevice;

    @Transactional
    public void onDeviceEvent(@ObservesAsync FingerprintEngine.DeviceEvent event) {
        // Only log status transitions
        if ("STATUS_CHANGE".equals(event.type) || "ONLINE".equals(event.type)) {
            try {
                UUID deviceId = UUID.fromString(event.deviceId);
                PhysicalDevice device = PhysicalDevice.findById(deviceId);
                
                if (device != null) {
                    DeviceStatus status = "ONLINE".equals(event.status) ? DeviceStatus.ONLINE : DeviceStatus.OFFLINE;

                    // Deduplicate: Don't insert if latest entry for this device already has the exact same status and ipAddress
                    DeviceStatusHistory latest = DeviceStatusHistory.find("physicalDevice.id = ?1 order by timestamp desc", deviceId)
                            .firstResult();
                    if (latest != null && latest.status == status && Objects.equals(latest.ipAddress, event.ipAddress)) {
                        return;
                    }

                    DeviceStatusHistory history = new DeviceStatusHistory();
                    history.physicalDevice = device;
                    history.status = status;
                    history.ipAddress = event.ipAddress;
                    history.timestamp = Instant.now();
                    history.persistAndFlush();

                    // Prune old history entries exceeding the configured max limit per device
                    List<DeviceStatusHistory> records = DeviceStatusHistory.find("physicalDevice.id = ?1 order by timestamp desc", deviceId).list();
                    if (records.size() > maxRecordsPerDevice) {
                        for (int i = maxRecordsPerDevice; i < records.size(); i++) {
                            records.get(i).delete();
                        }
                    }
                    
                    LOG.debugf("Recorded status history for device %s: %s", device.displayName, history.status);
                }
            } catch (Exception e) {
                LOG.error("Failed to record device status history", e);
            }
        }
    }
}
