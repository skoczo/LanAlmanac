package com.gnm.service.scanner;

import java.util.List;

public interface PortScannerEngine {
    List<Integer> scanPorts(String ipAddress, List<Integer> portsToScan, int delayMs);
}
