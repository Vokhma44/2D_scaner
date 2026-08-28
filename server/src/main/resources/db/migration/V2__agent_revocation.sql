ALTER TABLE agents
    ADD COLUMN revoked_at TIMESTAMPTZ NULL;

CREATE INDEX agents_active_token_idx ON agents (token_hash)
    WHERE revoked_at IS NULL;
