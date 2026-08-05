package com.gnm.dto.backup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class LanAlmanacBackup {
    public String version;
    public Instant exportDate;
    public List<PhysicalDeviceBackup> devices = new ArrayList<>();
    public List<NetworkLinkBackup> links = new ArrayList<>();
}
