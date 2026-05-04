-- V2: Create transactions table
-- Records every debit and credit on a wallet.
-- idempotency_key UNIQUE constraint ensures exactly-once semantics
-- when Kafka redelivers the same booking-event message.

CREATE TABLE transactions
(
    id               UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    wallet_id        UUID           NOT NULL REFERENCES wallets (id),
    type             VARCHAR(10)    NOT NULL CHECK (type IN ('DEBIT', 'CREDIT')),
    amount           DECIMAL(12, 2) NOT NULL CHECK (amount > 0),
    idempotency_key  VARCHAR(128)   NOT NULL UNIQUE,
    reference_id     UUID,
    reference_type   VARCHAR(20),
    status           VARCHAR(20)    NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED')),
    failure_reason   VARCHAR(500),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- Index for fetching transaction history per wallet (most recent first)
CREATE INDEX idx_transactions_wallet_id ON transactions (wallet_id, created_at DESC);

-- Index for correlating transactions back to a booking or ride
CREATE INDEX idx_transactions_reference_id ON transactions (reference_id);

-- The primary idempotency guard: prevents double-charge on Kafka retry
CREATE UNIQUE INDEX uq_transactions_idempotency_key ON transactions (idempotency_key);

COMMENT ON TABLE transactions IS 'Ledger of all wallet debits and credits.';
COMMENT ON COLUMN transactions.idempotency_key IS
    'Derived as bookingId:EVENT_TYPE. UNIQUE constraint ensures Kafka retries do not double-charge.';
COMMENT ON COLUMN transactions.reference_id IS
    'The booking or ride ID that triggered this transaction. NULL for manual top-ups.';
