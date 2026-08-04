-- Create TimescaleDB extension if available
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

-- 1. Physical Device
CREATE TABLE physical_device (
    id UUID PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    device_type VARCHAR(50),
    os_family VARCHAR(100),
    os_version VARCHAR(100),
    manufacturer VARCHAR(100),
    model VARCHAR(100),
    location_note TEXT,
    confidence_score DOUBLE PRECISION DEFAULT 1.0,
    manually_verified BOOLEAN DEFAULT FALSE,
    first_seen TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(50) DEFAULT 'UNKNOWN'
);

-- 2. Network Identity (semi-stable IP/MAC pairs mapping to Physical Device)
CREATE TABLE network_identity (
    id UUID PRIMARY KEY,
    physical_device_id UUID REFERENCES physical_device(id) ON DELETE CASCADE,
    ip_address VARCHAR(45) NOT NULL,
    mac_address VARCHAR(17) NOT NULL,
    hostname VARCHAR(255),
    dhcp_lease_id VARCHAR(255),
    first_seen TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen TIMESTAMP WITH TIME ZONE NOT NULL,
    current BOOLEAN DEFAULT TRUE
);

-- 3. Network Sighting (raw sightings log)
CREATE TABLE network_sighting (
    id BIGSERIAL PRIMARY KEY,
    network_identity_id UUID REFERENCES network_identity(id) ON DELETE CASCADE,
    ip_address VARCHAR(45) NOT NULL,
    mac_address VARCHAR(17) NOT NULL,
    source VARCHAR(50) NOT NULL,
    raw_metadata TEXT,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 4. Fingerprint Vector (device signatures)
CREATE TABLE fingerprint_vector (
    id UUID PRIMARY KEY,
    physical_device_id UUID REFERENCES physical_device(id) ON DELETE CASCADE,
    version INTEGER DEFAULT 1,
    dhcp_option55 TEXT,
    dhcp_option60 TEXT,
    tcp_fingerprint TEXT,
    mdns_services TEXT[],
    ssdp_usn TEXT,
    ssh_banner TEXT,
    http_server_header TEXT,
    tls_ja4 TEXT,
    tls_cert_subject TEXT,
    open_ports INTEGER[],
    mac_oui VARCHAR(10),
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 5. Credential (envelope encrypted secrets)
CREATE TABLE credential (
    id UUID PRIMARY KEY,
    physical_device_id UUID REFERENCES physical_device(id) ON DELETE CASCADE,
    label VARCHAR(255) NOT NULL,
    credential_type VARCHAR(50) NOT NULL,
    encrypted_payload BYTEA NOT NULL,
    nonce_payload BYTEA NOT NULL,
    username VARCHAR(255),
    port INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 6. Telemetry (Timescale hypertable for metrics)
CREATE TABLE telemetry (
    time TIMESTAMP WITH TIME ZONE NOT NULL,
    physical_device_id UUID REFERENCES physical_device(id) ON DELETE CASCADE,
    metric_name VARCHAR(100) NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    labels TEXT,
    PRIMARY KEY (time, physical_device_id, metric_name)
);

-- Convert telemetry into hypertable if create_hypertable exists
SELECT create_hypertable('telemetry', 'time', if_not_exists => TRUE);
