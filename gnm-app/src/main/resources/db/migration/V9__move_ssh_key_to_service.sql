ALTER TABLE physical_device DROP COLUMN ssh_host_key;
ALTER TABLE physical_device DROP COLUMN ssh_host_key_trusted;

ALTER TABLE network_service ADD COLUMN ssh_host_key VARCHAR(2048);
ALTER TABLE network_service ADD COLUMN ssh_host_key_trusted BOOLEAN DEFAULT FALSE;
