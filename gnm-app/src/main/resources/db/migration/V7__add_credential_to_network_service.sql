ALTER TABLE network_service ADD COLUMN credential_id UUID REFERENCES credential(id) ON DELETE SET NULL;
