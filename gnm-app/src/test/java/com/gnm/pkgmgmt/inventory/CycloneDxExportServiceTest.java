package com.gnm.pkgmgmt.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gnm.model.PhysicalDevice;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CycloneDxExportServiceTest {

    @Test
    void exportsValidCycloneDx15BomWithPurls() {
        CycloneDxExportService service = new CycloneDxExportService();

        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();
        device.displayName = "db-prod-01";
        device.osFamily = "Debian";
        device.osVersion = "12";

        DevicePackage p1 = new DevicePackage();
        p1.name = "libssl3";
        p1.version = "3.0.11-1~deb12u2";
        p1.arch = "amd64";
        p1.purl = "pkg:deb/debian/libssl3@3.0.11-1~deb12u2?arch=amd64";

        DevicePackage p2 = new DevicePackage();
        p2.name = "curl";
        p2.version = "7.88.1-10+deb12u5";
        p2.arch = "amd64";
        p2.purl = "pkg:deb/debian/curl@7.88.1-10+deb12u5?arch=amd64";

        Map<String, Object> bom = service.buildCycloneDxBom(device, List.of(p1, p2));

        assertEquals("CycloneDX", bom.get("bomFormat"));
        assertEquals("1.5", bom.get("specVersion"));
        assertEquals(1, bom.get("version"));
        assertTrue(bom.get("serialNumber").toString().startsWith("urn:uuid:"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components = (List<Map<String, Object>>) bom.get("components");
        assertEquals(2, components.size());
        assertEquals("library", components.get(0).get("type"));
        assertEquals("libssl3", components.get(0).get("name"));
        assertEquals(
                "pkg:deb/debian/libssl3@3.0.11-1~deb12u2?arch=amd64",
                components.get(0).get("purl"));
    }
}

