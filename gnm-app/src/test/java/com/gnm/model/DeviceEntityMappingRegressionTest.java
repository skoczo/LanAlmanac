package com.gnm.model;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import com.gnm.model.enums.DeviceStatus;
import com.gnm.model.enums.DeviceType;
import com.gnm.model.enums.PortScanState;
import com.gnm.pkgmgmt.profile.OsProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression contract test ensuring all complex column types (PostgreSQL native arrays text[]/integer[],
 * JSONB objects/maps, and ElementCollections) deserialize and serialize properly without Jackson or JDBC
 * mapping errors across schema migrations and Quarkus/Hibernate upgrades.
 */
@QuarkusTest
class DeviceEntityMappingRegressionTest {

    @Inject
    EntityManager entityManager;

    @BeforeEach
    void cleanUp() {
        QuarkusTransaction.requiringNew().run(() -> {
            FingerprintVector.deleteAll();
            NetworkIdentity.deleteAll();
            NetworkService.deleteAll();
            PhysicalDevice.deleteAll();
        });
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @DisplayName("Verify native PostgreSQL array literals (text[] & integer[]) in existing DB rows deserialize cleanly")
    void testNativePostgreSqlArrayDeserializationFromExistingDbData() {
        UUID deviceId = UUID.randomUUID();
        UUID fpId = UUID.randomUUID();

        // Simulate legacy or direct DB state using native PostgreSQL array syntax
        QuarkusTransaction.requiringNew().run(() -> {
            entityManager.createNativeQuery(
                    "INSERT INTO physical_device (id, display_name, first_seen, last_seen, status, management_state, port_scan_state, consecutive_missed_probes, reboot_required, os_profile_pinned) " +
                    "VALUES (:id, 'Legacy DB Device', now(), now(), 'ONLINE', 'DISCOVERED', 'FULLY_SCANNED', 0, false, false)")
                    .setParameter("id", deviceId)
                    .executeUpdate();

            entityManager.createNativeQuery(
                    "INSERT INTO fingerprint_vector (id, physical_device_id, captured_at, version, mdns_services, open_ports, tcp_fingerprint) " +
                    "VALUES (:fpId, :devId, now(), 1, " +
                    "'{\"_http._tcp.local\",\"_googlecast._tcp.local\",\"_matter._tcp.local\"}'::text[], " +
                    "'{80,443,8008,8080}'::integer[], " +
                    "'64:128:1:mss,nop,wscale,nop,nop,ts:df,id:0')")
                    .setParameter("fpId", fpId)
                    .setParameter("devId", deviceId)
                    .executeUpdate();
        });

        // 1. Panache ORM fetch test
        QuarkusTransaction.requiringNew().run(() -> {
            PhysicalDevice dev = PhysicalDevice.findById(deviceId);
            assertNotNull(dev, "Device must be queryable");
            assertEquals(1, dev.fingerprints.size(), "Fingerprint must be linked");
            FingerprintVector fp = dev.fingerprints.iterator().next();
            assertEquals(List.of("_http._tcp.local", "_googlecast._tcp.local", "_matter._tcp.local"), fp.mdnsServices);
            assertEquals(List.of(80, 443, 8008, 8080), fp.openPorts);
        });

        // 2. REST API /api/devices HTTP endpoint test (what UI hits)
        given()
            .when().get("/api/devices")
            .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].displayName", is("Legacy DB Device"))
                .body("[0].fingerprints[0].mdnsServices", hasItems("_http._tcp.local", "_googlecast._tcp.local", "_matter._tcp.local"))
                .body("[0].fingerprints[0].openPorts", hasItems(80, 443, 8008, 8080));
    }

    @Test
    @TestSecurity(user = "admin", roles = "gnm-admin")
    @DisplayName("Verify fully populated device graph with JSONB, ElementCollections, and Postgres Arrays round-trips")
    void testFullyPopulatedDeviceGraphRoundTrip() {
        QuarkusTransaction.requiringNew().run(() -> {
            OsProfile profile = OsProfile.find("slug", "debian-apt").firstResult();

            PhysicalDevice device = new PhysicalDevice();
            device.displayName = "Fully Populated Server";
            device.deviceType = DeviceType.SERVER;
            device.portScanState = PortScanState.FULLY_SCANNED;
            device.firstSeen = Instant.now();
            device.lastSeen = Instant.now();
            device.status = DeviceStatus.ONLINE;
            device.labels = Set.of("production", "datacenter-east", "critical");
            device.osFacts = Map.of("kernel", "Linux", "os.id", "ubuntu", "os.version_id", "24.04");
            device.osProfile = profile;
            device.persist();

            FingerprintVector fp = new FingerprintVector();
            fp.physicalDevice = device;
            fp.capturedAt = Instant.now();
            fp.mdnsServices = List.of("_ssh._tcp.local", "_sftp-ssh._tcp.local");
            fp.openPorts = List.of(22, 443);
            fp.sshHostKeys = List.of("SHA256:abcd1234efgh5678", "SHA256:9876zyxw5432");
            fp.dhcpOption55 = "1,3,6,15,119,252";
            fp.persist();

            NetworkIdentity identity = new NetworkIdentity();
            identity.physicalDevice = device;
            identity.ipAddress = "192.168.1.199";
            identity.macAddress = "00:11:22:33:44:55";
            identity.firstSeen = Instant.now();
            identity.lastSeen = Instant.now();
            identity.current = true;
            identity.persist();
        });

        // Query through API
        given()
            .when().get("/api/devices")
            .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].displayName", is("Fully Populated Server"))
                .body("[0].labels", hasItems("production", "datacenter-east", "critical"))
                .body("[0].osFacts.kernel", is("Linux"))
                .body("[0].osFacts['os.id']", is("ubuntu"))
                .body("[0].fingerprints[0].mdnsServices", hasItems("_ssh._tcp.local", "_sftp-ssh._tcp.local"))
                .body("[0].fingerprints[0].openPorts", hasItems(22, 443))
                .body("[0].fingerprints[0].sshHostKeys", hasItems("SHA256:abcd1234efgh5678", "SHA256:9876zyxw5432"));
    }

    @Test
    @DisplayName("Verify Panache HQL update and entity modification on array columns")
    void testPanacheHqlAndEntityUpdateOnArrayColumns() {
        UUID fpId = QuarkusTransaction.requiringNew().call(() -> {
            PhysicalDevice dev = new PhysicalDevice();
            dev.displayName = "Update Test Device";
            dev.firstSeen = Instant.now();
            dev.lastSeen = Instant.now();
            dev.persist();

            FingerprintVector fp = new FingerprintVector();
            fp.physicalDevice = dev;
            fp.capturedAt = Instant.now();
            fp.mdnsServices = List.of("_initial._tcp.local");
            fp.openPorts = List.of(80);
            fp.persist();
            return fp.id;
        });

        // Update via Entity
        QuarkusTransaction.requiringNew().run(() -> {
            FingerprintVector fp = FingerprintVector.findById(fpId);
            assertNotNull(fp);
            fp.mdnsServices = List.of("_initial._tcp.local", "_added._tcp.local");
            fp.openPorts = List.of(80, 8080);
        });

        // Verify changes persisted
        QuarkusTransaction.requiringNew().run(() -> {
            FingerprintVector fp = FingerprintVector.findById(fpId);
            assertNotNull(fp);
            assertEquals(List.of("_initial._tcp.local", "_added._tcp.local"), fp.mdnsServices);
            assertEquals(List.of(80, 8080), fp.openPorts);
        });
    }
}
