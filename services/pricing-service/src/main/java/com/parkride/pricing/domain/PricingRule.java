package com.parkride.pricing.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Pricing configuration for a specific parking lot.
 *
 * <p>The pricing engine selects the most recently effective active rule for a lot:
 * {@code effective_from <= now AND (effective_to IS NULL OR effective_to > now)}.
 *
 * <p>Final price formula:
 * <pre>
 *   price = base_rate × time_multiplier × occupancy_multiplier × duration_hours
 *         (capped at base_rate × time_multiplier × max_surge_cap × duration_hours)
 * </pre>
 */
@Entity
@Table(name = "pricing_rules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PricingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The parking lot this rule governs (cross-service reference by ID). */
    @Column(name = "lot_id", nullable = false)
    private UUID lotId;

    /**
     * Per-hour charge in INR before multipliers are applied.
     * Example: {@code 50.00} = ₹50 per hour.
     */
    @Column(name = "base_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal baseRate;

    /**
     * Declared capacity of the lot.
     * Used to compute {@code occupancy_ratio = Redis_count / lot_capacity}.
     */
    @Column(name = "lot_capacity", nullable = false)
    private int lotCapacity;

    // ── Peak hours ──────────────────────────────────────────────────────────

    /** Start of the peak pricing window (local time, e.g. 08:00). */
    @Column(name = "peak_hours_start", nullable = false)
    private LocalTime peakHoursStart;

    /** End of the peak pricing window (local time, e.g. 20:00). */
    @Column(name = "peak_hours_end", nullable = false)
    private LocalTime peakHoursEnd;

    // ── Multipliers ─────────────────────────────────────────────────────────

    /**
     * Multiplier applied during peak hours (≥ 1.0).
     * Example: {@code 1.50} = 50% more than base rate.
     */
    @Column(name = "peak_multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal peakMultiplier;

    /**
     * Multiplier applied during off-peak hours (typically ≤ 1.0 for discounts).
     * Example: {@code 0.80} = 20% less than base rate.
     */
    @Column(name = "off_peak_multiplier", nullable = false, precision = 4, scale = 2)
    private BigDecimal offPeakMultiplier;

    /**
     * Maximum surge multiplier cap (≥ 1.0).
     * Final price never exceeds {@code base_rate × time_multiplier × max_surge_cap}.
     */
    @Column(name = "max_surge_cap", nullable = false, precision = 4, scale = 2)
    private BigDecimal maxSurgeCap;

    // ── Validity window ─────────────────────────────────────────────────────

    /** Rule becomes effective at this point in time. */
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    /** Rule expires at this point in time (null = open-ended / no expiry). */
    @Column(name = "effective_to")
    private Instant effectiveTo;

    // ── Audit ───────────────────────────────────────────────────────────────

    /** User ID of the ADMIN/OPERATOR who created this rule. */
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    private void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (effectiveFrom == null) effectiveFrom = createdAt;
    }
}
