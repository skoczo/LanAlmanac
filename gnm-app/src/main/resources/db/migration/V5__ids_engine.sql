-- Phase 6: IDS Detection Mode Tables

CREATE TABLE global_setting (
    key VARCHAR(255) PRIMARY KEY,
    value TEXT
);

-- Default to Discovery mode
INSERT INTO global_setting (key, value) VALUES ('APP_MODE', 'DISCOVERY');

CREATE TABLE threat_event (
    id UUID PRIMARY KEY,
    severity VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    physical_device_id UUID REFERENCES physical_device(id) ON DELETE CASCADE,
    ip_address VARCHAR(45),
    mac_address VARCHAR(17),
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved BOOLEAN DEFAULT FALSE
);
