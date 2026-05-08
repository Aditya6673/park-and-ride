package com.parkride.pricing.service;

import com.parkride.pricing.domain.PricingRule;
import com.parkride.pricing.dto.PriceEstimateResponse;
import com.parkride.pricing.dto.SurgeInfoResponse;
import com.parkride.pricing.repository.PricingRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Core dynamic pricing algorithm for the Park &amp; Ride platform.
 *
 * <h2>Formula</h2>
 * <pre>
 *   price = base_rate × time_multiplier × occupancy_multiplier × duration_hours
 *   price = min(price, base_rate × time_multiplier × max_surge_cap × duration_hours)
 * </pre>
 *
 * <h2>Multiplier resolution</h2>
 * <ul>
 *   <li><b>time_multiplier:</b> {@code peakMultiplier} if request falls inside
 *       {@code [peakHoursStart, peakHoursEnd)}, else {@code offPeakMultiplier}.</li>
 *   <li><b>occupancy_multiplier:</b> {@code 1.0 + occupancyRatio × (maxSurgeCap − 1.0)}
 *       where {@code occupancyRatio = min(currentOccupancy / lotCapacity, 1.0)}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingEngineService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal MINUTES_IN_HOUR = BigDecimal.valueOf(60);
    private static final int PRICE_SCALE = 2;

    private final PricingRuleRepository ruleRepository;
    private final OccupancyTrackingService occupancyService;

    // ── Price estimation ────────────────────────────────────────────────────

    /**
     * Computes the price estimate for parking at {@code lotId} starting at
     * {@code startTime} for {@code durationMinutes}.
     *
     * @throws NoSuchElementException if no active pricing rule exists for the lot
     */
    @Transactional(readOnly = true)
    public PriceEstimateResponse calculatePrice(UUID lotId, int durationMinutes, Instant startTime) {

        PricingRule rule = ruleRepository.findActiveRuleForLot(lotId, startTime)
                .orElseThrow(() -> new NoSuchElementException(
                        "No active pricing rule for lotId: " + lotId));

        BigDecimal timeMultiplier  = resolveTimeMultiplier(rule, startTime);
        BigDecimal occupancyMultiplier = resolveOccupancyMultiplier(rule, lotId);

        BigDecimal durationHours = BigDecimal.valueOf(durationMinutes)
                .divide(MINUTES_IN_HOUR, 6, RoundingMode.HALF_UP);

        // price = base × time × occupancy × hours
        BigDecimal rawPrice = rule.getBaseRate()
                .multiply(timeMultiplier)
                .multiply(occupancyMultiplier)
                .multiply(durationHours);

        // cap at base × time × maxSurgeCap × hours
        BigDecimal cap = rule.getBaseRate()
                .multiply(timeMultiplier)
                .multiply(rule.getMaxSurgeCap())
                .multiply(durationHours);

        BigDecimal finalPrice = rawPrice.min(cap)
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

        log.debug("Price for lot {} ({}min): base={} × time={} × occ={} × {}h = {} (cap={})",
                lotId, durationMinutes,
                rule.getBaseRate(), timeMultiplier, occupancyMultiplier,
                durationHours, finalPrice, cap.setScale(PRICE_SCALE, RoundingMode.HALF_UP));

        return PriceEstimateResponse.builder()
                .lotId(lotId)
                .durationMinutes(durationMinutes)
                .startTime(startTime)
                .baseRatePerHour(rule.getBaseRate())
                .timeMultiplier(timeMultiplier)
                .occupancyMultiplier(occupancyMultiplier.setScale(4, RoundingMode.HALF_UP))
                .totalPrice(finalPrice)
                .currency("INR")
                .cached(false)
                .build();
    }

    // ── Surge info ──────────────────────────────────────────────────────────

    /**
     * Returns the current surge status for the given lot.
     *
     * @throws NoSuchElementException if no active pricing rule exists for the lot
     */
    @Transactional(readOnly = true)
    public SurgeInfoResponse getSurgeInfo(UUID lotId) {
        PricingRule rule = ruleRepository.findActiveRuleForLot(lotId, Instant.now())
                .orElseThrow(() -> new NoSuchElementException(
                        "No active pricing rule for lotId: " + lotId));

        int currentOccupancy = occupancyService.getOccupancy(lotId);
        BigDecimal ratio = computeOccupancyRatio(currentOccupancy, rule.getLotCapacity());
        BigDecimal surge = computeOccupancyMultiplier(ratio, rule.getMaxSurgeCap());

        return SurgeInfoResponse.builder()
                .lotId(lotId)
                .currentOccupancy(currentOccupancy)
                .lotCapacity(rule.getLotCapacity())
                .occupancyRatio(ratio.setScale(4, RoundingMode.HALF_UP))
                .surgeMultiplier(surge.setScale(4, RoundingMode.HALF_UP))
                .maxSurgeCap(rule.getMaxSurgeCap())
                .occupancyLevel(toOccupancyLevel(ratio))
                .build();
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Returns {@code peakMultiplier} if {@code startTime} falls inside the peak window,
     * otherwise returns {@code offPeakMultiplier}.
     */
    BigDecimal resolveTimeMultiplier(PricingRule rule, Instant startTime) {
        LocalTime localTime = startTime.atZone(IST).toLocalTime();
        boolean isPeak = !localTime.isBefore(rule.getPeakHoursStart())
                && localTime.isBefore(rule.getPeakHoursEnd());
        return isPeak ? rule.getPeakMultiplier() : rule.getOffPeakMultiplier();
    }

    /**
     * Computes the occupancy multiplier: {@code 1.0 + ratio × (maxSurgeCap − 1.0)}.
     * At 0% occupancy → 1.0 (no surge). At 100% occupancy → maxSurgeCap.
     */
    BigDecimal resolveOccupancyMultiplier(PricingRule rule, UUID lotId) {
        int occupancy = occupancyService.getOccupancy(lotId);
        BigDecimal ratio = computeOccupancyRatio(occupancy, rule.getLotCapacity());
        return computeOccupancyMultiplier(ratio, rule.getMaxSurgeCap());
    }

    BigDecimal computeOccupancyRatio(int currentOccupancy, int capacity) {
        if (capacity <= 0) return BigDecimal.ZERO;
        BigDecimal ratio = BigDecimal.valueOf(currentOccupancy)
                .divide(BigDecimal.valueOf(capacity), 6, RoundingMode.HALF_UP);
        return ratio.min(BigDecimal.ONE).max(BigDecimal.ZERO);
    }

    BigDecimal computeOccupancyMultiplier(BigDecimal occupancyRatio, BigDecimal maxSurgeCap) {
        // multiplier = 1.0 + ratio × (maxSurgeCap - 1.0)
        BigDecimal surgeRange = maxSurgeCap.subtract(BigDecimal.ONE);
        return BigDecimal.ONE.add(occupancyRatio.multiply(surgeRange));
    }

    private String toOccupancyLevel(BigDecimal ratio) {
        double r = ratio.doubleValue();
        if (r < 0.5)  return "LOW";
        if (r < 0.75) return "MEDIUM";
        if (r < 1.0)  return "HIGH";
        return "FULL";
    }
}
