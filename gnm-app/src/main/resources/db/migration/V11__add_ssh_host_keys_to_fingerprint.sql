CREATE TABLE fingerprint_vector_ssh_keys (
    fingerprint_vector_id UUID NOT NULL,
    ssh_host_key VARCHAR(2048),
    FOREIGN KEY (fingerprint_vector_id) REFERENCES fingerprint_vector(id) ON DELETE CASCADE
);
