CREATE TABLE physical_device_labels (
    physical_device_id UUID NOT NULL,
    label VARCHAR(255) NOT NULL,
    CONSTRAINT fk_device_labels FOREIGN KEY (physical_device_id) REFERENCES physical_device(id) ON DELETE CASCADE,
    CONSTRAINT uq_device_label UNIQUE (physical_device_id, label)
);
