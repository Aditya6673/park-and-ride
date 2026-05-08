package com.parkride.pricing.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Request body for creating or updating a {@code PricingRule}.
 */
@Data
public class PricingRuleRequest {

    @NotNull(message = "lotId is required")
    private UUID lotId;

    @NotNull(message = "baseRate is required")
    @DecimalMin(value = "0.01", message = "baseRate must be greater than 0")
    @Digits(integer = 8, fraction = 2, message = "baseRate must have at most 8 integer and 2 decimal digits")
    private BigDecimal baseRate;

    @Positive(message = "lotCapacity must be positive")
    private int lotCapacity;

    @NotNull(message = "peakHoursStart is required")
    private LocalTime peakHoursStart;

    @NotNull(message = "peakHoursEnd is required")
    private LocalTime peakHoursEnd;

    @NotNull(message = "peakMultiplier is required")
    @DecimalMin(value = "1.0", message = "peakMultiplier must be at least 1.0")
    @Digits(integer = 2, fraction = 2, message = "peakMultiplier max 4 digits")
    private BigDecimal peakMultiplier;

    @NotNull(message = "offPeakMultiplier is required")
    @DecimalMin(value = "0.1", message = "offPeakMultiplier must be at least 0.1")
    @Digits(integer = 2, fraction = 2, message = "offPeakMultiplier max 4 digits")
    private BigDecimal offPeakMultiplier;

    @NotNull(message = "maxSurgeCap is required")
    @DecimalMin(value = "1.0", message = "maxSurgeCap must be at least 1.0")
    @Digits(integer = 2, fraction = 2, message = "maxSurgeCap max 4 digits")
    private BigDecimal maxSurgeCap;

    /** Optional: defaults to now if not provided. */
    private Instant effectiveFrom;

    /** Optional: null = open-ended rule. */
    private Instant effectiveTo;
}
