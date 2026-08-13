-- Add hostname column to fingerprint_vector to support persisted hostname-based device merging.
-- Previously hostname was @Transient and not persisted, causing locally-administered MAC devices
-- (e.g., phones with randomized MACs) to create phantom devices on every MAC change.
ALTER TABLE fingerprint_vector ADD COLUMN IF NOT EXISTS hostname VARCHAR(255);
