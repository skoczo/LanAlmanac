ALTER TABLE physical_device ADD COLUMN ssh_host_key VARCHAR(2048);
ALTER TABLE physical_device ADD COLUMN ssh_host_key_trusted BOOLEAN DEFAULT FALSE;
