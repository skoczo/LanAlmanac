-- V18: Replace time-based offline timeout with probe-based consecutive failure counter.
-- consecutive_missed_probes tracks how many ICMP sweep cycles in a row a device was not seen.
-- A device is marked OFFLINE only after N consecutive missed probes (configurable, default 2).
ALTER TABLE physical_device ADD COLUMN IF NOT EXISTS consecutive_missed_probes INT NOT NULL DEFAULT 0;

-- Default threshold: 2 consecutive missed ICMP probe cycles before marking device OFFLINE
INSERT INTO global_setting (key, value) VALUES ('DEVICE_OFFLINE_MISSED_PROBES_THRESHOLD', '2') ON CONFLICT (key) DO NOTHING;
