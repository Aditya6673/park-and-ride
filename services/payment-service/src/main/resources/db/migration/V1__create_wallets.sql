-- V1: Create wallets table
-- One wallet per user, enforced by UNIQUE constraint on user_id.
-- version column enables JPA optimistic locking (@Version).

CREATE TABLE wallets
(
    id             UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id        UUID           NOT NULL UNIQUE,
    balance        DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    loyalty_points INT            NOT NULL DEFAULT 0,
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_wallets_user_id ON wallets (user_id);

-- Auto-update updated_at on every row change
CREATE OR REPLACE FUNCTION set_updated_at()
    RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER wallets_set_updated_at
    BEFORE UPDATE
    ON wallets
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE wallets IS 'One wallet per registered user. Balance is in INR.';
COMMENT ON COLUMN wallets.version IS 'JPA optimistic lock counter — incremented on every UPDATE.';
COMMENT ON COLUMN wallets.balance IS 'Current balance in INR. Cannot go below zero (enforced by WalletService).';
