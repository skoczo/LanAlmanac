-- V25: Normalized device packages, inventory scan history, and vulnerability tables

CREATE TABLE device_package_scan (
    id                  UUID PRIMARY KEY,
    physical_device_id  UUID NOT NULL REFERENCES physical_device(id) ON DELETE CASCADE,
    os_profile_id       UUID REFERENCES os_profile(id) ON DELETE SET NULL,
    credential_id       UUID REFERENCES credential(id) ON DELETE SET NULL,
    credential_username VARCHAR(128),
    used_fallback       BOOLEAN NOT NULL DEFAULT FALSE,
    status              VARCHAR(32) NOT NULL,
    package_count       INT NOT NULL DEFAULT 0,
    skipped_lines       INT NOT NULL DEFAULT 0,
    error_code          VARCHAR(64),
    error_message       TEXT,
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ
);

CREATE INDEX idx_device_package_scan_device ON device_package_scan(physical_device_id, started_at DESC);

CREATE TABLE device_package (
    id                  UUID PRIMARY KEY,
    physical_device_id  UUID NOT NULL REFERENCES physical_device(id) ON DELETE CASCADE,
    purl                VARCHAR(512) NOT NULL,
    ecosystem           VARCHAR(64) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    version             VARCHAR(128) NOT NULL,
    arch                VARCHAR(64) NOT NULL DEFAULT '',
    source_name         VARCHAR(255) NOT NULL,
    source_version      VARCHAR(128) NOT NULL,
    last_scan_id        UUID REFERENCES device_package_scan(id) ON DELETE SET NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (physical_device_id, name, arch)
);

CREATE INDEX idx_device_package_device ON device_package(physical_device_id);
CREATE INDEX idx_device_package_purl ON device_package(purl);
CREATE INDEX idx_device_package_source ON device_package(ecosystem, source_name);

CREATE TABLE vulnerability_advisory (
    id                  VARCHAR(64) PRIMARY KEY,
    cve_aliases         JSONB NOT NULL DEFAULT '[]'::jsonb,
    severity            VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    cvss_score          DOUBLE PRECISION,
    summary             TEXT,
    references_json     JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE device_vulnerability (
    id                  UUID PRIMARY KEY,
    physical_device_id  UUID NOT NULL REFERENCES physical_device(id) ON DELETE CASCADE,
    package_name        VARCHAR(255) NOT NULL,
    installed_version   VARCHAR(128) NOT NULL,
    fixed_version       VARCHAR(128),
    fix_available_now   BOOLEAN NOT NULL DEFAULT FALSE,
    advisory_id         VARCHAR(64) NOT NULL REFERENCES vulnerability_advisory(id) ON DELETE CASCADE,
    status              VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    suppression_note    TEXT,
    suppressed_by       VARCHAR(128),
    first_detected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_detected_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    UNIQUE (physical_device_id, package_name, advisory_id)
);

CREATE INDEX idx_device_vulnerability_device ON device_vulnerability(physical_device_id, status);
CREATE INDEX idx_device_vulnerability_advisory ON device_vulnerability(advisory_id);

