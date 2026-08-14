-- V17__add_fingerprint_correlation_event.sql
CREATE TABLE fingerprint_correlation_event (
    id UUID PRIMARY KEY,
    physical_device_id UUID REFERENCES physical_device(id) ON DELETE CASCADE,
    ip_address VARCHAR(45) NOT NULL,
    mac_address VARCHAR(17) NOT NULL,
    hostname VARCHAR(255),
    decision_type VARCHAR(50) NOT NULL,
    confidence_score DOUBLE PRECISION NOT NULL,
    details TEXT,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_correlation_device_time ON fingerprint_correlation_event(physical_device_id, timestamp DESC);
