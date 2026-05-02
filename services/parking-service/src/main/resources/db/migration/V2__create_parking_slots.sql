-- V2__create_parking_slots.sql
-- Creates the parking_slots table linked to parking_lots.

CREATE TABLE parking_slots (
    id              UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    lot_id          UUID         NOT NULL REFERENCES parking_lots (id) ON DELETE CASCADE,
    slot_number     VARCHAR(20)  NOT NULL,
    slot_type       VARCHAR(20)  NOT NULL DEFAULT 'CAR'
                        CHECK (slot_type IN ('CAR', 'BIKE', 'EV', 'DISABLED')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE'
                        CHECK (status IN ('AVAILABLE', 'RESERVED', 'OCCUPIED', 'MAINTENANCE')),
    price_per_hour  DECIMAL(10, 2) NOT NULL,
    floor           VARCHAR(10),
    position_index  INT          NOT NULL DEFAULT 0,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_slot_number_per_lot UNIQUE (lot_id, slot_number)
);

CREATE INDEX idx_slots_lot_status ON parking_slots (lot_id, status);
CREATE INDEX idx_slots_lot_type   ON parking_slots (lot_id, slot_type);
-- Partial index — only active available slots matter for booking queries
CREATE INDEX idx_slots_available  ON parking_slots (lot_id, slot_type, price_per_hour)
    WHERE status = 'AVAILABLE' AND is_active = TRUE;
