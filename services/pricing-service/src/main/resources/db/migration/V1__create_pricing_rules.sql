-- ============================================================================
-- V1 — Create pricing_rules table
-- ============================================================================
-- Dynamic pricing configuration per parking lot.
-- Multiple rules may exist per lot, with non-overlapping effective date ranges.
-- The pricing engine always selects the MOST RECENTLY effective active rule.
-- ============================================================================

CREATE TABLE pricing_rules
(
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The lot this rule applies to (FK to parking_service — cross-service reference by ID only)
    lot_id              UUID          NOT NULL,

    -- Per-hour base rate in INR (e.g., 50.00 = ₹50/hr)
    base_rate           NUMERIC(10,2) NOT NULL CHECK (base_rate > 0),

    -- Known capacity of the lot; used to calculate occupancy ratio for surge
    lot_capacity        INT           NOT NULL CHECK (lot_capacity > 0),

    -- Peak hour window (server-local time; ideally IST for Indian deployments)
    peak_hours_start    TIME          NOT NULL,
    peak_hours_end      TIME          NOT NULL,

    -- Multipliers applied to base_rate
    -- peak_multiplier   >= 1.0 (e.g., 1.5 = 50% more during peak)
    -- off_peak_multiplier: typically <= 1.0 for discounts, but >=  0.1 enforced
    peak_multiplier     NUMERIC(4,2)  NOT NULL DEFAULT 1.50 CHECK (peak_multiplier >= 1.0),
    off_peak_multiplier NUMERIC(4,2)  NOT NULL DEFAULT 0.80 CHECK (off_peak_multiplier >= 0.1),

    -- Maximum surge cap as a multiplier on base_rate (e.g., 2.0 = never more than 2× base)
    max_surge_cap       NUMERIC(4,2)  NOT NULL DEFAULT 2.00 CHECK (max_surge_cap >= 1.0),

    -- Rule validity window (open-ended if effective_to is NULL)
    effective_from      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    effective_to        TIMESTAMPTZ,

    -- Audit
    created_by          UUID          NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    -- Sanity: effective_to must be after effective_from when set
    CONSTRAINT chk_pricing_rules_dates CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- Fast lookup by lot — used by PricingEngineService on every price request
CREATE INDEX idx_pricing_rules_lot_id    ON pricing_rules (lot_id);

-- Composite index for "find active rule for this lot at this time"
CREATE INDEX idx_pricing_rules_effective ON pricing_rules (lot_id, effective_from, effective_to);

COMMENT ON TABLE  pricing_rules                 IS 'Dynamic pricing configuration per parking lot.';
COMMENT ON COLUMN pricing_rules.base_rate       IS 'Per-hour charge in INR before multipliers.';
COMMENT ON COLUMN pricing_rules.lot_capacity    IS 'Used to compute occupancy_ratio = current_occupancy / lot_capacity.';
COMMENT ON COLUMN pricing_rules.max_surge_cap   IS 'Final price never exceeds base_rate × time_multiplier × max_surge_cap.';
