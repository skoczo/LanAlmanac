-- V26: Device available updates table and update check / reboot flags on physical_device

CREATE TABLE device_available_update (
    id                  UUID PRIMARY KEY,
    physical_device_id  UUID NOT NULL REFERENCES physical_device(id) ON DELETE CASCADE,
    package_name        VARCHAR(255) NOT NULL,
    arch                VARCHAR(64) NOT NULL DEFAULT '',
    current_version     VARCHAR(128),
    candidate_version   VARCHAR(128) NOT NULL,
    is_security_update  BOOLEAN NOT NULL DEFAULT FALSE,
    checked_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (physical_device_id, package_name, arch)
);

CREATE INDEX idx_device_available_update_device ON device_available_update(physical_device_id);

ALTER TABLE physical_device
    ADD COLUMN updates_last_checked_at TIMESTAMPTZ,
    ADD COLUMN reboot_required BOOLEAN NOT NULL DEFAULT FALSE;

