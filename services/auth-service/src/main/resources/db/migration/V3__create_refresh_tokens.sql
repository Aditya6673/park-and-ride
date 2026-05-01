-- V3__create_refresh_tokens.sql
-- Refresh tokens stored as SHA-256 hashes — raw token strings are never persisted.

CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    is_revoked  BOOLEAN     NOT NULL DEFAULT FALSE,
    device_info VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_refresh_tokens_hash    ON refresh_tokens (token_hash);
CREATE        INDEX idx_refresh_tokens_user   ON refresh_tokens (user_id);
CREATE        INDEX idx_refresh_tokens_expiry ON refresh_tokens (expires_at) WHERE is_revoked = FALSE;
