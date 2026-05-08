package com.parkride.pricing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response for {@code GET /api/v1/pricing/parking} — computed price estimate.
 */
@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PriceEstimateResponse {

    UUID   lotId;

    /** Duration in minutes as requested. */
    int    durationMinutes;

    /** Requested start time. */
    Instant startTime;

    // ── Price breakdown ──────────────────────────────────────────────────────

    /** Base hourly rate before any multipliers. */
    BigDecimal baseRatePerHour;

    /**
     * Time-of-day multiplier applied.
     * {@code peakMultiplier} during peak hours, {@code offPeakMultiplier} otherwise.
     */
    BigDecimal timeMultiplier;

    /**
     * Occupancy-based surge multiplier (1.0 = no surge, up to maxSurgeCap).
     * Formula: {@code 1.0 + occupancyRatio × (maxSurgeCap − 1.0)}
     */
    BigDecimal occupancyMultiplier;

    /**
     * Final computed price for the full duration.
     * {@code = baseRatePerHour × timeMultiplier × occupancyMultiplier × durationHours}
     * (capped at {@code baseRatePerHour × timeMultiplier × maxSurgeCap × durationHours})
     */
    BigDecimal totalPrice;

    /** Currency code — always INR for this platform. */
    String currency;

    /** Whether this response was served from the Redis price cache. */
    boolean cached;
}
