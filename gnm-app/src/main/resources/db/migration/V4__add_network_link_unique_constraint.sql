CREATE UNIQUE INDEX idx_unique_network_link 
ON network_link (LEAST(source_device_id, target_device_id), GREATEST(source_device_id, target_device_id));
