CREATE TABLE network_service (
    id UUID PRIMARY KEY,
    physical_device_id UUID NOT NULL REFERENCES physical_device(id) ON DELETE CASCADE,
    label VARCHAR(255),
    service_type VARCHAR(50) NOT NULL,
    protocol VARCHAR(10) DEFAULT 'TCP',
    port INTEGER NOT NULL,
    manageable BOOLEAN DEFAULT TRUE,
    discovered BOOLEAN DEFAULT TRUE,
    first_seen TIMESTAMPTZ NOT NULL,
    last_seen TIMESTAMPTZ NOT NULL
);

-- Migrate existing credential port field into network_service records
INSERT INTO network_service (id, physical_device_id, label, service_type, port, first_seen, last_seen)
SELECT gen_random_uuid(), physical_device_id,
       CASE credential_type
           WHEN 'SSH' THEN 'SSH Host'
           WHEN 'SSH_KEY' THEN 'SSH Host'
           WHEN 'SNMP' THEN 'SNMP'
           ELSE label
       END,
       CASE credential_type
           WHEN 'SSH' THEN 'SSH'
           WHEN 'SSH_KEY' THEN 'SSH'
           WHEN 'SNMP' THEN 'SNMP'
           ELSE 'HTTP'
       END,
       COALESCE(port, 22),
       NOW(), NOW()
FROM credential
WHERE credential_type IN ('SSH', 'SSH_KEY', 'SNMP')
ON CONFLICT DO NOTHING;
