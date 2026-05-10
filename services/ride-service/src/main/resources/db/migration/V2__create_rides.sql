-- V2 — Create rides table
CREATE TABLE rides (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID          NOT NULL,
    booking_id          UUID,
    driver_id           UUID,
    vehicle_id          UUID,
    vehicle_type        VARCHAR(20)   NOT NULL,
    status              VARCHAR(30)   NOT NULL DEFAULT 'REQUESTED',
    pickup_lat          DOUBLE PRECISION NOT NULL,
    pickup_lng          DOUBLE PRECISION NOT NULL,
    pickup_address      VARCHAR(300),
    dropoff_lat         DOUBLE PRECISION NOT NULL,
    dropoff_lng         DOUBLE PRECISION NOT NULL,
    dropoff_address     VARCHAR(300),
    estimated_fare      NUMERIC(10,2),
    final_fare          NUMERIC(10,2),
    distance_km         DOUBLE PRECISION,
    is_pooled           BOOLEAN       NOT NULL DEFAULT FALSE,
    pool_group_id       UUID,
    passenger_rating    INTEGER       CHECK (passenger_rating BETWEEN 1 AND 5),
    cancellation_reason VARCHAR(500),
    requested_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    pickup_at           TIMESTAMPTZ,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_rides_user_id   ON rides (user_id);
CREATE INDEX idx_rides_driver_id ON rides (driver_id);
CREATE INDEX idx_rides_status    ON rides (status);
CREATE INDEX idx_rides_booking   ON rides (booking_id);
