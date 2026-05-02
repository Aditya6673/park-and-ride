-- V3__create_bookings.sql
-- Creates the bookings table with double-booking prevention.

CREATE TABLE bookings (
    id                   UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id              UUID         NOT NULL,
    slot_id              UUID         NOT NULL REFERENCES parking_slots (id),
    start_time           TIMESTAMPTZ  NOT NULL,
    end_time             TIMESTAMPTZ  NOT NULL,
    status               VARCHAR(30)  NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING','CONFIRMED','CHECKED_IN','COMPLETED','CANCELLED','NO_SHOW')),
    total_amount         DECIMAL(10, 2) NOT NULL,
    qr_token             VARCHAR(1024),
    check_in_time        TIMESTAMPTZ,
    check_out_time       TIMESTAMPTZ,
    cancellation_reason  VARCHAR(500),
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_booking_times CHECK (end_time > start_time)
);

-- Standard lookup indexes
CREATE INDEX idx_bookings_user   ON bookings (user_id);
CREATE INDEX idx_bookings_slot   ON bookings (slot_id);
CREATE INDEX idx_bookings_status ON bookings (status);
CREATE INDEX idx_bookings_qr     ON bookings (qr_token) WHERE qr_token IS NOT NULL;

-- Double-booking prevention:
-- Only one ACTIVE booking (not cancelled/no-show) per slot per overlapping time window.
-- The EXCLUDE constraint prevents overlapping time ranges for the same slot.
CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE bookings ADD CONSTRAINT no_double_booking
    EXCLUDE USING GIST (
        slot_id WITH =,
        tstzrange(start_time, end_time, '[)') WITH &&
    )
    WHERE (status NOT IN ('CANCELLED', 'NO_SHOW'));
