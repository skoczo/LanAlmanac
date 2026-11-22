-- Drop telemetry table and remove telemetry related global settings
DROP TABLE IF EXISTS telemetry CASCADE;
DELETE FROM global_setting WHERE key IN ('TELEMETRY_RETENTION_DAYS', 'POLL_INTERVAL_SEC');

