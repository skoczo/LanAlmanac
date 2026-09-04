-- V22: Device Status History

CREATE TABLE device_status_history (
    id UUID PRIMARY KEY,
    physical_device_id UUID NOT NULL REFERENCES physical_device(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL,
    ip_address VARCHAR(255),
    timestamp TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_device_status_history_device ON device_status_history(physical_device_id);
CREATE INDEX idx_device_status_history_timestamp ON device_status_history(timestamp DESC);
