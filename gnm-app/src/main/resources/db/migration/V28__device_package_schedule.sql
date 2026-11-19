-- Per-Device Package Management Schedules (ADR-5)
CREATE TABLE device_package_schedule (
    device_id               UUID PRIMARY KEY REFERENCES physical_device(id) ON DELETE CASCADE,
    inventory_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    inventory_cron          VARCHAR(64) NOT NULL DEFAULT '0 3 * * *',
    next_inventory_run_at   TIMESTAMPTZ,
    last_inventory_run_at   TIMESTAMPTZ,

    update_check_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    update_check_cron       VARCHAR(64) NOT NULL DEFAULT '0 4 * * *',
    next_update_check_run_at TIMESTAMPTZ,
    last_update_check_run_at TIMESTAMPTZ,

    auto_patch_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    auto_patch_mode         VARCHAR(32) NOT NULL DEFAULT 'SECURITY_ONLY',
    auto_patch_cron         VARCHAR(64) NOT NULL DEFAULT '0 5 * * 0',
    next_auto_patch_run_at  TIMESTAMPTZ,
    last_auto_patch_run_at  TIMESTAMPTZ,

    timezone                VARCHAR(64) NOT NULL DEFAULT 'UTC',
    updated_by              VARCHAR(128),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pkg_sched_next_inv ON device_package_schedule(next_inventory_run_at) WHERE inventory_enabled = TRUE;
CREATE INDEX idx_pkg_sched_next_upd ON device_package_schedule(next_update_check_run_at) WHERE update_check_enabled = TRUE;
CREATE INDEX idx_pkg_sched_next_pat ON device_package_schedule(next_auto_patch_run_at) WHERE auto_patch_enabled = TRUE;

