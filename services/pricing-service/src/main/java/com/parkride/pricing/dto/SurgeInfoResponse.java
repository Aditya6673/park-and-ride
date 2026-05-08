package com.parkride.pricing.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response for {@code GET /api/v1/pricing/surge/{lotId}} — current surge status.
 */
@Value
@Builder
public class SurgeInfoResponse {

    UUID lotId;

    /** Current number of active bookings tracked via Kafka. */
    int currentOccupancy;

    /** Declared capacity of the lot from its pricing rule. */
    int lotCapacity;

    /** {@code currentOccupancy / lotCapacity}, clamped to [0, 1]. */
    BigDecimal occupancyRatio;

    /**
     * Current surge multiplier (1.0 = no surge, up to maxSurgeCap).
     * Formula: {@code 1.0 + occupancyRatio × (maxSurgeCap − 1.0)}
     */
    BigDecimal surgeMultiplier;

    /** The configured maximum surge cap for this lot. */
    BigDecimal maxSurgeCap;

    /** Human-readable label: "LOW", "MEDIUM", "HIGH", "FULL". */
    String occupancyLevel;
}
