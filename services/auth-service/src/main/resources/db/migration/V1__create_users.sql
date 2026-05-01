-- V1__create_users.sql
-- Creates the users table with all columns used by the User entity.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE users (
    id                              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email                           VARCHAR(255)  NOT NULL,
    password_hash                   VARCHAR(255)  NOT NULL,
    first_name                      VARCHAR(100),
    last_name                       VARCHAR(100),
    phone                           VARCHAR(20),
    is_verified                     BOOLEAN       NOT NULL DEFAULT FALSE,
    is_enabled                      BOOLEAN       NOT NULL DEFAULT TRUE,
    failed_login_attempts           INT           NOT NULL DEFAULT 0,
    locked_until                    TIMESTAMPTZ,
    verification_token_hash         VARCHAR(64),
    verification_token_expires_at   TIMESTAMPTZ,
    password_reset_token_hash       VARCHAR(64),
    password_reset_token_expires_at TIMESTAMPTZ,
    created_at                      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_users_email ON users (email);
CREATE INDEX idx_users_verification_token ON users (verification_token_hash) WHERE verification_token_hash IS NOT NULL;
CREATE INDEX idx_users_reset_token        ON users (password_reset_token_hash) WHERE password_reset_token_hash IS NOT NULL;
