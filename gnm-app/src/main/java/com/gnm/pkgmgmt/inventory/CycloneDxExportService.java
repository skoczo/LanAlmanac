package com.gnm.pkgmgmt.inventory;

import com.gnm.model.PhysicalDevice;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class CycloneDxExportService {

    public Map<String, Object> buildCycloneDxBom(
            PhysicalDevice device, List<DevicePackage> packages) {
        Map<String, Object> bom = new LinkedHashMap<>();
        bom.put("bomFormat", "CycloneDX");
        bom.put("specVersion", "1.5");
        bom.put("serialNumber", "urn:uuid:" + UUID.randomUUID());
        bom.put("version", 1);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timestamp", Instant.now().toString());
        if (device != null) {
            Map<String, Object> rootComponent = new LinkedHashMap<>();
            rootComponent.put("type", "device");
            rootComponent.put(
                    "bom-ref",
                    device.id != null ? device.id.toString() : "unknown-device");
            rootComponent.put(
                    "name",
                    device.displayName != null ? device.displayName : "unknown");
            if (device.osVersion != null) {
                rootComponent.put("version", device.osVersion);
            }
            metadata.put("component", rootComponent);
        }
        bom.put("metadata", metadata);

        List<Map<String, Object>> components = new ArrayList<>();
        if (packages != null) {
            for (DevicePackage pkg : packages) {
                if (pkg == null) {
                    continue;
                }
                Map<String, Object> comp = new LinkedHashMap<>();
                comp.put("type", "library");
                comp.put("bom-ref", pkg.purl != null ? pkg.purl : pkg.name + "@" + pkg.version);
                comp.put("name", pkg.name);
                comp.put("version", pkg.version);
                if (pkg.purl != null) {
                    comp.put("purl", pkg.purl);
                }
                components.add(comp);
            }
        }
        bom.put("components", components);
        return bom;
    }
}

