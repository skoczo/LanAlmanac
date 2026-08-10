# GreatNetworkManager - Automated Testing Scenarios

This document outlines the core E2E and UI test scenarios currently implemented to verify the application's behavior. The tests cover backend discovery, monitoring, status tracking, and frontend user interactions.

## 1. Device Discovery & Fingerprinting

These tests verify the backend's ability to discover devices on the network, either actively via ICMP sweeps or passively by sniffing broadcast traffic (e.g., DHCP).

*   **Scenario 1.1: Active Device Discovery**
    *   **Description:** Simulates an administrator initiating a manual discovery on an IP address. Verifies that the backend probes the device, successfully extracts a basic footprint, and adds it to the database with the correct nested identities.
    *   **Implementation:** [testActiveScanDiscoversDevices](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/DiscoveryAndFingerprintingTest.java#L16)

*   **Scenario 1.2: Passive DHCP Sniffing**
    *   **Description:** Uses the traffic generator container to simulate a DHCP broadcast on the Docker bridge network. Asserts that the backend's `PassivePacketListener` intercepts the packet, extracts the source IP/MAC, and creates a `DHCP_SNIFF` sighting resulting in a newly managed device.
    *   **Implementation:** [testPassiveDhcpSniffing](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/DiscoveryAndFingerprintingTest.java#L54)

*   **Scenario 1.3: Fingerprint Merging (Ephemeral IP Handling) [PENDING]**
    *   **Description:** Simulates a device changing its IP and MAC address but maintaining its TCP/DHCP fingerprint vector (e.g., matching JA4 and DHCP Option 55). Verifies the `FingerprintEngine` calculates a `ConfidenceScore` above the `MERGE_THRESHOLD` and updates the existing `PhysicalDevice` instead of creating a duplicate.
    *   **Implementation:** *Not yet implemented*

*   **Scenario 1.4: Passive mDNS Sniffing [PENDING]**
    *   **Description:** Simulates an mDNS broadcast on port 5353. Verifies that the listener parses the service records (e.g., `_ssh._tcp`) and updates the device's open ports and metadata.
    *   **Implementation:** *Not yet implemented*

## 2. Device Status Tracking

These tests verify the system's ability to accurately transition device states between ONLINE and OFFLINE based on real-time container availability and scheduled inactivity sweeps.

*   **Scenario 2.1: Device Goes Offline (Backend Inactivity Sweep)**
    *   **Description:** Provisions an active device, stops its associated Docker container, and forces the `DEVICE_OFFLINE_TIMEOUT_MINUTES` setting to 0. Waits for the scheduled backend inactivity job to run and verifies the device's state changes from `ONLINE` to `OFFLINE`.
    *   **Implementation:** [testDeviceOfflineStatusChange](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/DeviceStatusTest.java#L16)

*   **Scenario 2.2: Device Goes Offline (UI Reflection)**
    *   **Description:** Verifies that the frontend Dashboard correctly reflects an offline state when the backend API provides an updated status for a device.
    *   **Implementation:** [Status Change (Online -> Offline)](file:///home/skoczo/workspace/GreatNetworkManager/frontend/tests/device-status.spec.ts#L37)

*   **Scenario 2.3: Device Comes Online (UI Reflection)**
    *   **Description:** Verifies that the frontend Dashboard removes offline alarms/badges when a device's status transitions back to ONLINE.
    *   **Implementation:** [Status Change (Offline -> Online)](file:///home/skoczo/workspace/GreatNetworkManager/frontend/tests/device-status.spec.ts#L68)

## 3. Monitoring & Topology

These tests ensure that the system can successfully poll devices for detailed telemetry and topology information.

*   **Scenario 3.1: SNMP Polling**
    *   **Description:** Ensures that the backend can poll an SNMP-enabled router simulator.
    *   **Implementation:** [testSnmpPolling](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/MonitoringTest.java#L15)

*   **Scenario 3.2: LLDP Topology Discovery**
    *   **Description:** Ensures that LLDP data is successfully collected and parsed to establish network topology.
    *   **Implementation:** [testLldpTopologyDiscovery](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/MonitoringTest.java#L44)

## 4. Manual UI Operations

These tests use Playwright to simulate user interactions within the frontend React application.

*   **Scenario 4.1: Manual Device Addition**
    *   **Description:** Verifies the user flow for manually adding a device via the UI form.
    *   **Implementation:** [Manual Device Add](file:///home/skoczo/workspace/GreatNetworkManager/frontend/tests/manual-operations.spec.ts#L34)

*   **Scenario 4.2: Device Fields Modification**
    *   **Description:** Verifies the user flow for editing an existing device's metadata.
    *   **Implementation:** [Device Fields Modification](file:///home/skoczo/workspace/GreatNetworkManager/frontend/tests/manual-operations.spec.ts#L49)

*   **Scenario 4.3: Credential Vault Management**
    *   **Description:** Verifies the user flow for managing sensitive device credentials via the secure vault UI.
    *   **Implementation:** [Credential Vault Management](file:///home/skoczo/workspace/GreatNetworkManager/frontend/tests/manual-operations.spec.ts#L61)

## 5. Data Management

These tests ensure data integrity and compatibility across different application versions.

*   **Scenario 5.1: Database Backup & Restore**
    *   **Description:** Verifies that older version database backups (V1) can be successfully imported into the current schema.
    *   **Implementation:** [testExportAndImportV1](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/BackupCompatibilityTest.java#L29)

## 6. Credential Vault Security

These tests verify the envelope encryption and sealed-key architecture of the credential vault.

*   **Scenario 6.1: Vault Key Sealing and Unsealing**
    *   **Description:** Verifies that supplying the Master Passphrase correctly derives the Argon2id Key Encryption Key (KEK) to unseal the Data Encryption Key (DEK).
    *   **Implementation:** [testVaultKeySealingAndUnsealing](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/CredentialVaultTest.java#L39)

*   **Scenario 6.2: Encryption at Rest Verification**
    *   **Description:** Creates a credential using the API, bypasses the application to query the PostgreSQL database directly, and asserts that the stored credential is AES-256-GCM encrypted ciphertext and completely unreadable.
    *   **Implementation:** [testEncryptionAtRestVerification](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/CredentialVaultTest.java#L59)

## 7. Remote Access & WebSockets

These tests cover the real-time integrations and SSH proxying capabilities of the application.

*   **Scenario 7.1: WebSocket Real-time UI Updates**
    *   **Description:** Triggers a backend state change (e.g., device goes offline) and verifies that a JSON event is pushed over the `/ws/events` WebSocket, causing the frontend Zustand store to update reactively without HTTP polling.
    *   **Implementation:** [testWebSocketRealtimeUiUpdates](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/RemoteAccessTest.java#L64)

*   **Scenario 7.2: SSH Terminal Proxy Session**
    *   **Description:** Verifies that the backend can establish an SSH session to a target device using unsealed vault credentials and successfully stream the bidirectional terminal I/O over WebSocket to an xterm.js frontend instance.
    *   **Implementation:** [testSshTerminalProxySession](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/RemoteAccessTest.java#L91)

## 8. Security & Threat Detection (SSH Host Keys)

These tests ensure the system detects, alarms, and prevents Man-in-the-Middle (MitM) attacks or unauthorized device swapping by tracking SSH Host Keys (similar to `known_hosts`).

*   **Scenario 8.1: SSH Host Key Change Detected on Periodic Scan**
    *   **Description:** Simulates an SSH host key changing on a target device while the system is in "Strict" host key checking mode. Verifies that the periodic monitoring scan detects the mismatch and raises a critical security alarm without requiring the user to attempt a manual connection.
    *   **Implementation:** [testSshHostKeyChangeDetectedOnPeriodicScan](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/SecurityAndThreatTest.java#L64)

*   **Scenario 8.2: Connection Blocked and Alarm Raised on Manual Connect**
    *   **Description:** The user attempts to open an SSH proxy terminal to a device whose host key has changed. Verifies the backend strictly refuses to connect, drops the WebSocket session, and pushes a real-time security alarm to the UI indicating a potential MitM attack.
    *   **Implementation:** [testConnectionBlockedAndAlarmRaisedOnManualConnect](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/SecurityAndThreatTest.java#L140)

*   **Scenario 8.3: Alarm Auto-Mitigation on Host Key Reversion**
    *   **Description:** Simulates an SSH host key changing, triggering an alarm, and then reverting back to the original known host key (e.g., a rogue device was removed and the original device reconnected). Verifies that the alarm is downgraded/cleared but an informational security event is logged noting that the key temporarily changed.
    *   **Implementation:** [testAlarmAutoMitigationOnHostKeyReversion](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/SecurityAndThreatTest.java#L93)

*   **Scenario 8.4: Host Key Trust on First Connect (TOFU)**
    *   **Description:** Verifies the Trust-On-First-Use (TOFU) behavior where the system automatically accepts and stores the SSH host key on the very first successful connection to a newly discovered device.
    *   **Implementation:** [testHostKeyTrustOnFirstConnectTofu](file:///home/skoczo/workspace/GreatNetworkManager/gnm-app/src/test/java/com/gnm/SecurityAndThreatTest.java#L181)
