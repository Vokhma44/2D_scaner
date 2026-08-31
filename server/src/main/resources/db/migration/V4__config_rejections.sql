ALTER TABLE agents
    ADD COLUMN rejected_config_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN config_rejection_reason VARCHAR(500);
