INSERT INTO global_setting (key, value) VALUES ('oidc.enabled', 'false') ON CONFLICT DO NOTHING;
INSERT INTO global_setting (key, value) VALUES ('oidc.authority.url', '') ON CONFLICT DO NOTHING;
INSERT INTO global_setting (key, value) VALUES ('oidc.client.id', '') ON CONFLICT DO NOTHING;
