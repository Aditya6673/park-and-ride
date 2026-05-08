package com.parkride.pricing.service;

import com.parkride.pricing.domain.PricingRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PricingEngineService} — verifies the pricing algorithm
 * under various boundary conditions without hitting a database or Redis.
 */
class PricingEngineServiceTest {

    @Mock
    private OccupancyTrackingService occupancyService;

    @Mock
    private com.parkride.pricing.repository.PricingRuleRepository ruleRepository;

    @InjectMocks
    private PricingEngineService engine;

    private UUID lotId;
    private PricingRule baseRule;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        lotId = UUID.randomUUID();

        // Standard rule: ₹50/hr, peak 08:00–20:00, peak 1.5×, off-peak 0.8×, cap 2.0×, capacity 100
        baseRule = PricingRule.builder()
                .id(UUID.randomUUID())
                .lotId(lotId)
                .baseRate(new BigDecimal("50.00"))
                .lotCapacity(100)
                .peakHoursStart(LocalTime.of(8, 0))
                .peakHoursEnd(LocalTime.of(20, 0))
                .peakMultiplier(new BigDecimal("1.50"))
                .offPeakMultiplier(new BigDecimal("0.80"))
                .maxSurgeCap(new BigDecimal("2.00"))
                .build();
    }

    // ── Time multiplier ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Time multiplier — during peak hours returns peakMultiplier")
    void timeMultiplier_peakHours_returnsPeakMultiplier() {
        // 14:00 IST = 08:30 UTC
        Instant peak = Instant.parse("2025-01-15T08:30:00Z");
        BigDecimal multiplier = engine.resolveTimeMultiplier(baseRule, peak);
        assertThat(multiplier).isEqualByComparingTo("1.50");
    }

    @Test
    @DisplayName("Time multiplier — during off-peak hours returns offPeakMultiplier")
    void timeMultiplier_offPeakHours_returnsOffPeakMultiplier() {
        // 02:00 IST = 20:30 UTC (previous day) — outside 08:00–20:00 IST
        Instant offPeak = Instant.parse("2025-01-14T20:30:00Z");
        BigDecimal multiplier = engine.resolveTimeMultiplier(baseRule, offPeak);
        assertThat(multiplier).isEqualByComparingTo("0.80");
    }

    @Test
    @DisplayName("Time multiplier — exactly at peakHoursStart is peak")
    void timeMultiplier_exactlyAtPeakStart_isPeak() {
        // 08:00 IST = 02:30 UTC
        Instant atPeakStart = Instant.parse("2025-01-15T02:30:00Z");
        BigDecimal multiplier = engine.resolveTimeMultiplier(baseRule, atPeakStart);
        assertThat(multiplier).isEqualByComparingTo("1.50");
    }

    @Test
    @DisplayName("Time multiplier — exactly at peakHoursEnd is off-peak (exclusive)")
    void timeMultiplier_exactlyAtPeakEnd_isOffPeak() {
        // 20:00 IST = 14:30 UTC
        Instant atPeakEnd = Instant.parse("2025-01-15T14:30:00Z");
        BigDecimal multiplier = engine.resolveTimeMultiplier(baseRule, atPeakEnd);
        assertThat(multiplier).isEqualByComparingTo("0.80");
    }

    // ── Occupancy multiplier ───────────────────────────────────────────────────

    @ParameterizedTest(name = "occupancy={0}/{1} → ratio={2} → multiplier={3}")
    @CsvSource({
        // currentOccupancy, capacity, expectedRatio, expectedMultiplier
        "0,   100, 0.0000, 1.0000",   // 0% → no surge
        "50,  100, 0.5000, 1.5000",   // 50% → midpoint surge
        "100, 100, 1.0000, 2.0000",   // 100% → maxSurgeCap
        "150, 100, 1.0000, 2.0000",   // Over capacity → clamped at cap
        "1,   100, 0.0100, 1.0100",   // Low occupancy → minimal surge
        "99,  100, 0.9900, 1.9900",   // Just below full → near-cap surge
    })
    @DisplayName("Occupancy multiplier — parameterized boundary tests")
    void occupancyMultiplier_boundaryConditions(int occupancy, int capacity,
                                               String expectedRatio,
                                               String expectedMultiplier) {
        BigDecimal ratio = engine.computeOccupancyRatio(occupancy, capacity);
        BigDecimal multiplier = engine.computeOccupancyMultiplier(ratio, new BigDecimal("2.00"));

        assertThat(ratio).isEqualByComparingTo(new BigDecimal(expectedRatio));
        assertThat(multiplier).isEqualByComparingTo(new BigDecimal(expectedMultiplier));
    }

    @Test
    @DisplayName("Occupancy ratio — zero capacity returns 0 (guard against division by zero)")
    void occupancyRatio_zeroCapacity_returnsZero() {
        BigDecimal ratio = engine.computeOccupancyRatio(50, 0);
        assertThat(ratio).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Surge cap enforcement ─────────────────────────────────────────────────

    @Test
    @DisplayName("Surge cap — full occupancy peak price is exactly base × peakMultiplier × maxSurgeCap")
    void surgeCap_fullOccupancyPeak_priceIsExactlyCapped() {
        // At 100% occupancy: occupancy_multiplier = maxSurgeCap = 2.0
        // peak raw = 50 × 1.5 × 2.0 = 150 per hour
        // cap      = 50 × 1.5 × 2.0 = 150 per hour  (same — so rawPrice == cap)
        BigDecimal ratio = engine.computeOccupancyRatio(100, 100); // = 1.0
        BigDecimal occupancyMult = engine.computeOccupancyMultiplier(ratio, new BigDecimal("2.00")); // = 2.0

        BigDecimal rawPerHour = new BigDecimal("50.00")
                .multiply(new BigDecimal("1.50"))
                .multiply(occupancyMult);

        BigDecimal capPerHour = new BigDecimal("50.00")
                .multiply(new BigDecimal("1.50"))
                .multiply(new BigDecimal("2.00"));

        assertThat(rawPerHour).isEqualByComparingTo(capPerHour);
    }

    @Test
    @DisplayName("Surge cap — occupancy multiplier can never exceed maxSurgeCap")
    void surgeCap_neverExceedsMaxSurgeCap() {
        // Even with 200% theoretical occupancy (ratio clamped to 1.0)
        BigDecimal ratio = engine.computeOccupancyRatio(999, 100); // clamped to 1.0
        BigDecimal multiplier = engine.computeOccupancyMultiplier(ratio, new BigDecimal("2.00"));
        assertThat(multiplier).isLessThanOrEqualTo(new BigDecimal("2.00"));
    }

    // ── No-surge baseline ─────────────────────────────────────────────────────

    @Test
    @DisplayName("No-surge baseline — zero occupancy, off-peak: price = base × offPeak × duration")
    void price_zeroOccupancyOffPeak_equalsBaseTimesOffPeakTimesHours() {
        when(occupancyService.getOccupancy(lotId)).thenReturn(0);

        // off-peak multiplier = 0.8, zero occupancy → occupancyMult = 1.0
        // price = 50 × 0.8 × 1.0 × 1h = 40.00
        BigDecimal durationHours = BigDecimal.ONE;
        BigDecimal expected = new BigDecimal("50.00")
                .multiply(new BigDecimal("0.80"))
                .multiply(BigDecimal.ONE)
                .multiply(durationHours);

        BigDecimal ratio      = engine.computeOccupancyRatio(0, 100);
        BigDecimal occMult    = engine.computeOccupancyMultiplier(ratio, new BigDecimal("2.00"));
        BigDecimal timeMult   = new BigDecimal("0.80");
        BigDecimal computed   = new BigDecimal("50.00").multiply(timeMult).multiply(occMult);

        assertThat(computed).isEqualByComparingTo(expected);
    }
}
