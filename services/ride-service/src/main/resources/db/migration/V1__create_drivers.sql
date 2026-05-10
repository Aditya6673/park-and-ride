-- V1 — Create drivers table
-- Ride Service owns driver registration and GPS location.
CREATE TABLE drivers (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID         NOT NULL UNIQUE,
    name                 VARCHAR(100) NOT NULL,
    phone                VARCHAR(20)  NOT NULL,
    license_number       VARCHAR(50)  NOT NULL UNIQUE,
    vehicle_type         VARCHAR(20)  NOT NULL,
    vehicle_plate        VARCHAR(20)  NOT NULL,
    vehicle_model        VARCHAR(100),
    status               VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE',
    current_lat          DOUBLE PRECISION,
    current_lng          DOUBLE PRECISION,
    location_updated_at  TIMESTAMPTZ,
    rating               NUMERIC(3,2) NOT NULL DEFAULT 0.00,
    total_rides          INTEGER      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_drivers_user_id     ON drivers (user_id);
CREATE INDEX idx_drivers_status      ON drivers (status);
CREATE INDEX idx_drivers_vehicle_type ON drivers (vehicle_type);
