package com.gnm.dto;

import java.util.List;

public class ScanProgress {
    public int queuedDevices;
    public int activeScans;
    public List<String> currentlyScanningIPs;
    public int totalScanned;
}
