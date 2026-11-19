-- V27: Patch jobs and persistent streaming patch job logs

CREATE TABLE patch_job (
    id                  UUID PRIMARY KEY,
    physical_device_id  UUID NOT NULL REFERENCES physical_device(id) ON DELETE CASCADE,
    os_profile_id       UUID REFERENCES os_profile(id) ON DELETE SET NULL,
    credential_id       UUID REFERENCES credential(id) ON DELETE SET NULL,
    credential_username VARCHAR(128),
    used_fallback       BOOLEAN NOT NULL DEFAULT FALSE,
    triggered_by        VARCHAR(128) NOT NULL,
    mode                VARCHAR(32) NOT NULL,
    requested_packages  JSONB NOT NULL DEFAULT '[]'::jsonb,
    status              VARCHAR(32) NOT NULL,
    current_phase       VARCHAR(32),
    reboot_required     BOOLEAN,
    exit_code           INT,
    error_code          VARCHAR(64),
    error_summary       TEXT,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_patch_job_device ON patch_job(physical_device_id, created_at DESC);

CREATE TABLE patch_job_log (
    job_id              UUID NOT NULL REFERENCES patch_job(id) ON DELETE CASCADE,
    seq                 BIGINT NOT NULL,
    stream              VARCHAR(16) NOT NULL,
    ts                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    line                TEXT NOT NULL,
    PRIMARY KEY (job_id, seq)
);

CREATE INDEX idx_patch_job_log_ts ON patch_job_log(ts);

