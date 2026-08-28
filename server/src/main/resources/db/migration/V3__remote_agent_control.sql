ALTER TABLE agents
    ADD COLUMN desired_config JSONB NOT NULL DEFAULT '{}'::JSONB,
    ADD COLUMN config_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN applied_config_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN revoke_phones_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN applied_revoke_phones_revision BIGINT NOT NULL DEFAULT 0;
