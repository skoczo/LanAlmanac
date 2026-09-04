-- User management table for local authentication
CREATE TABLE gnm_user (
    id UUID PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(512) NOT NULL,
    display_name VARCHAR(255),
    role VARCHAR(50) NOT NULL DEFAULT 'gnm-viewer',
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- OIDC role claim path setting (e.g., 'groups' for Authentik, 'realm_access/roles' for Keycloak)
INSERT INTO global_setting (key, value) VALUES ('oidc.role.claim.path', 'groups') ON CONFLICT (key) DO NOTHING;
