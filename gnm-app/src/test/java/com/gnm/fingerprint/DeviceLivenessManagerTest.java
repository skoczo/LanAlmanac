package com.gnm.fingerprint;

import com.gnm.discovery.IcmpSweeper;
import com.gnm.model.NetworkIdentity;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.DeviceStatus;
import io.quarkus.test.junit.QuarkusTest;
import com.gnm.model.DeviceStatusHistory;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
public class DeviceLivenessManagerTest {

    @Inject
    DeviceLivenessManager livenessManager;

    @InjectMock
    IcmpSweeper icmpSweeperMock;

    private PhysicalDevice testDevice;
    private String testIp = "10.0.0.99";

    @BeforeEach
    @Transactional
    public void setup() {
        // Clear cache between tests to avoid interference
        livenessManager.clearCache();

        com.gnm.model.GlobalSetting setting = com.gnm.model.GlobalSetting.findById("DEVICE_OFFLINE_MISSED_PROBES_THRESHOLD");
        if (setting == null) {
            setting = new com.gnm.model.GlobalSetting();
            setting.key = "DEVICE_OFFLINE_MISSED_PROBES_THRESHOLD";
            setting.persist();
        }
        setting.value = "2";
        setting.persist();

        testDevice = new PhysicalDevice();
        testDevice.displayName = "Test Device";
        testDevice.status = DeviceStatus.ONLINE;
        testDevice.firstSeen = Instant.now();
        testDevice.lastSeen = Instant.now();
        testDevice.persist();

        NetworkIdentity id = new NetworkIdentity();
        id.physicalDevice = testDevice;
        id.ipAddress = testIp;
        id.macAddress = "00:11:22:33:44:55";
        id.firstSeen = Instant.now();
        id.lastSeen = Instant.now();
        id.current = true;
        id.persist();

        testDevice.identities.add(id);
        testDevice.persist();
    }

    @AfterEach
    @Transactional
    public void teardown() {
        livenessManager.clearCache();
        DeviceStatusHistory.deleteAll();
        NetworkIdentity.deleteAll();
        PhysicalDevice.deleteAll();
    }

    @Test
    public void testRecordActivity() {
        livenessManager.recordActivity(testIp);
        Instant lastSeen = livenessManager.getLastSeen(testIp);
        assertNotNull(lastSeen);
        assertTrue(Instant.now().plusSeconds(1).isAfter(lastSeen));
    }

    @Test
    @Transactional
    public void testEvaluatePresence_ActiveDevice() {
        // Device is active in memory
        livenessManager.recordActivity(testIp);
        
        // Mock ICMP to fail so if it mistakenly checks, we know (it shouldn't check)
        when(icmpSweeperMock.checkTargetReachable(anyString())).thenReturn(false);

        livenessManager.evaluatePresence();

        PhysicalDevice dbDevice = PhysicalDevice.findById(testDevice.id);
        assertEquals(DeviceStatus.ONLINE, dbDevice.status);
        Mockito.verify(icmpSweeperMock, Mockito.never()).checkTargetReachable(anyString());
    }

    @Test
    public void testEvaluatePresence_WarningDevice_RespondsToPing() throws InterruptedException {
        // Push device lastSeen backward into warning zone (effActiveCheck=60, effOffline=120)
        pushDeviceLastSeenBack(90);

        // Mock ICMP to succeed
        Mockito.when(icmpSweeperMock.checkTargetReachable(Mockito.anyString())).thenReturn(true);

        livenessManager.evaluatePresence();

        // The evaluation triggers an async check, wait a bit
        Thread.sleep(100);

        PhysicalDevice dbDevice = PhysicalDevice.findById(testDevice.id);
        assertEquals(DeviceStatus.ONLINE, dbDevice.status);
        assertNotNull(livenessManager.getLastSeen(testIp)); // Memory cache should be updated
        Mockito.verify(icmpSweeperMock, Mockito.atLeastOnce()).checkTargetReachable(testIp);
    }

    @Test
    public void testEvaluatePresence_WarningDevice_FailsPing() throws InterruptedException {
        // Push device lastSeen backward into warning zone (90s)
        pushDeviceLastSeenBack(90);

        // Mock ICMP to fail
        Mockito.when(icmpSweeperMock.checkTargetReachable(Mockito.anyString())).thenReturn(false);

        livenessManager.evaluatePresence();

        // Wait for async check
        Thread.sleep(100);

        // Should remain ONLINE because it failed ping but hasn't reached offline threshold
        PhysicalDevice dbDevice = PhysicalDevice.findById(testDevice.id);
        assertEquals(DeviceStatus.ONLINE, dbDevice.status);
        assertNull(livenessManager.getLastSeen(testIp)); // Cache not updated
    }

    @Test
    public void testEvaluatePresence_OfflineDevice() {
        // Push device lastSeen backward beyond offline threshold (effOffline=120s)
        pushDeviceLastSeenBack(150);

        livenessManager.evaluatePresence();

        // It should immediately mark it offline without pinging
        PhysicalDevice dbDevice = PhysicalDevice.findById(testDevice.id);
        assertEquals(DeviceStatus.OFFLINE, dbDevice.status);
    }

    @Transactional
    protected void pushDeviceLastSeenBack(int seconds) {
        testDevice = PhysicalDevice.findById(testDevice.id);
        testDevice.lastSeen = Instant.now().minusSeconds(seconds);
        testDevice.persist();
    }
}
