CREATE TABLE network_link (
    id UUID PRIMARY KEY,
    source_device_id UUID NOT NULL,
    target_device_id UUID NOT NULL,
    source_interface VARCHAR(255),
    target_interface VARCHAR(255),
    discovery_protocol VARCHAR(50) NOT NULL,
    last_verified TIMESTAMP NOT NULL,
    CONSTRAINT fk_link_source FOREIGN KEY (source_device_id) REFERENCES physical_device(id) ON DELETE CASCADE,
    CONSTRAINT fk_link_target FOREIGN KEY (target_device_id) REFERENCES physical_device(id) ON DELETE CASCADE
);
