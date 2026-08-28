CREATE TABLE enrollment_tokens (
    token_hash  CHAR(64) PRIMARY KEY,
    label       VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NULL
);

CREATE TABLE agents (
    id            UUID PRIMARY KEY,
    token_hash    CHAR(64) NOT NULL UNIQUE,
    display_name  VARCHAR(200) NOT NULL,
    host_name     VARCHAR(255) NOT NULL,
    agent_version VARCHAR(50) NOT NULL,
    os_name       VARCHAR(100) NOT NULL,
    os_version    VARCHAR(100) NOT NULL,
    enrolled_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX agents_last_seen_idx ON agents (last_seen_at DESC);
CREATE INDEX enrollment_tokens_expiry_idx ON enrollment_tokens (expires_at)
    WHERE consumed_at IS NULL;
