-- V1__create_parking_lots.sql
-- Creates the parking_lots table.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE parking_lots (
    id              UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    name            VARCHAR(200) NOT NULL,
    address         VARCHAR(500) NOT NULL,
    city            VARCHAR(100) NOT NULL,
    state           VARCHAR(100),
    latitude        DECIMAL(10, 8) NOT NULL,
    longitude       DECIMAL(11, 8) NOT NULL,
    total_slots     INT          NOT NULL DEFAULT 0,
    contact_phone   VARCHAR(20),
    description     VARCHAR(1000),
    image_url       VARCHAR(500),
    operator_id     UUID,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_lots_city   ON parking_lots (city);
CREATE INDEX idx_lots_active ON parking_lots (is_active);
-- Composite index for proximity-search queries (filter by city first, then sort by coords)
CREATE INDEX idx_lots_city_location ON parking_lots (city, latitude, longitude);
