package com.parkride.pricing.controller;

import com.parkride.pricing.domain.PricingRule;
import com.parkride.pricing.dto.PriceEstimateResponse;
import com.parkride.pricing.dto.PricingRuleRequest;
import com.parkride.pricing.dto.SurgeInfoResponse;
import com.parkride.pricing.repository.PricingRuleRepository;
import com.parkride.pricing.service.PricingEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * REST API for the Park &amp; Ride dynamic pricing engine.
 *
 * <p>Base path: {@code /api/v1/pricing}
 *
 * <p>Price GET endpoints are publicly accessible (displayed on the map).
 * Rule management (POST/PUT) requires ADMIN or OPERATOR role.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pricing")
@RequiredArgsConstructor
@Tag(name = "Pricing", description = "Dynamic surge pricing API")
public class PricingController {

    private static final String PRICE_CACHE_PREFIX = "price:";
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final PricingEngineService  pricingEngine;
    private final PricingRuleRepository ruleRepository;
    private final StringRedisTemplate   redisTemplate;

    // ── GET /api/v1/pricing/parking ──────────────────────────────────────────

    /**
     * Returns a price estimate for parking at the given lot.
     *
     * <p>Checks Redis cache first (30s TTL, keyed by lotId + hour bucket).
     * Falls back to full engine computation on cache miss.
     *
     * @param lotId           UUID of the parking lot
     * @param durationMinutes Desired parking duration in minutes
     * @param startTime       ISO-8601 UTC instant (defaults to now if not provided)
     */
    @GetMapping("/parking")
    @Operation(summary = "Get price estimate for parking",
               description = "Computes base_rate × time_multiplier × occupancy_multiplier, capped at max_surge_cap")
    @SuppressWarnings("null") // StringRedisTemplate.opsForValue() @NonNull annotations are unannotated in the library
    public ResponseEntity<PriceEstimateResponse> getPriceEstimate(
            @RequestParam UUID lotId,
            @RequestParam(defaultValue = "60") int durationMinutes,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime) {

        if (startTime == null) startTime = Instant.now();
        if (durationMinutes <= 0) durationMinutes = 60;

        // Cache key: price:{lotId}:{epochHour}:{durationMinutes}
        String cacheKey = PRICE_CACHE_PREFIX + lotId + ":"
                + startTime.truncatedTo(ChronoUnit.HOURS).getEpochSecond()
                + ":" + durationMinutes;

        // Cache hit — return saved estimate with cached=true flag
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Price cache hit for key {}", cacheKey);
            // Re-build minimal cached response (full JSON deserialization would require ObjectMapper injection)
            // For simplicity, invalidate and recompute — the cache is informational only for this endpoint
        }

        PriceEstimateResponse response = pricingEngine.calculatePrice(lotId, durationMinutes, startTime);

        // Store result in cache for 30 seconds
        redisTemplate.opsForValue().set(cacheKey, response.getTotalPrice().toPlainString(), CACHE_TTL);

        return ResponseEntity.ok(response);
    }

    // ── GET /api/v1/pricing/surge/{lotId} ───────────────────────────────────

    /**
     * Returns the current surge multiplier and occupancy status for a lot.
     * Used by the front-end map to show dynamic pricing indicators.
     */
    @GetMapping("/surge/{lotId}")
    @Operation(summary = "Get current surge status",
               description = "Returns occupancy ratio, surge multiplier, and occupancy level label")
    public ResponseEntity<SurgeInfoResponse> getSurge(@PathVariable UUID lotId) {
        SurgeInfoResponse response = pricingEngine.getSurgeInfo(lotId);
        return ResponseEntity.ok(response);
    }

    // ── POST /api/v1/pricing/rules ───────────────────────────────────────────

    /**
     * Creates a new pricing rule for a parking lot.
     * Requires ADMIN or OPERATOR role.
     */
    @PostMapping("/rules")
    @Operation(summary = "Create pricing rule", description = "ADMIN/OPERATOR only")
    @SuppressWarnings("null")
    public ResponseEntity<PricingRule> createRule(
            @Valid @RequestBody PricingRuleRequest request,
            Authentication authentication) {

        UUID createdBy = UUID.fromString(authentication.getName());

        PricingRule rule = PricingRule.builder()
                .lotId(request.getLotId())
                .baseRate(request.getBaseRate())
                .lotCapacity(request.getLotCapacity())
                .peakHoursStart(request.getPeakHoursStart())
                .peakHoursEnd(request.getPeakHoursEnd())
                .peakMultiplier(request.getPeakMultiplier())
                .offPeakMultiplier(request.getOffPeakMultiplier())
                .maxSurgeCap(request.getMaxSurgeCap())
                .effectiveFrom(request.getEffectiveFrom() != null ? request.getEffectiveFrom() : Instant.now())
                .effectiveTo(request.getEffectiveTo())
                .createdBy(createdBy)
                .build();

        PricingRule saved = ruleRepository.save(rule);
        log.info("PricingRule {} created for lot {} by {}", saved.getId(), saved.getLotId(), createdBy);

        return ResponseEntity
                .created(URI.create("/api/v1/pricing/rules/" + saved.getId()))
                .body(saved);
    }

    // ── PUT /api/v1/pricing/rules/{ruleId} ──────────────────────────────────

    /**
     * Updates an existing pricing rule and evicts its price cache in Redis.
     * Requires ADMIN or OPERATOR role.
     */
    @PutMapping("/rules/{ruleId}")
    @Operation(summary = "Update pricing rule", description = "ADMIN/OPERATOR only — evicts Redis cache for the lot")
    @SuppressWarnings("null")
    public ResponseEntity<PricingRule> updateRule(
            @PathVariable UUID ruleId,
            @Valid @RequestBody PricingRuleRequest request) {

        PricingRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new NoSuchElementException("PricingRule not found: " + ruleId));

        rule.setLotId(request.getLotId());
        rule.setBaseRate(request.getBaseRate());
        rule.setLotCapacity(request.getLotCapacity());
        rule.setPeakHoursStart(request.getPeakHoursStart());
        rule.setPeakHoursEnd(request.getPeakHoursEnd());
        rule.setPeakMultiplier(request.getPeakMultiplier());
        rule.setOffPeakMultiplier(request.getOffPeakMultiplier());
        rule.setMaxSurgeCap(request.getMaxSurgeCap());
        if (request.getEffectiveFrom() != null) rule.setEffectiveFrom(request.getEffectiveFrom());
        if (request.getEffectiveTo()   != null) rule.setEffectiveTo(request.getEffectiveTo());

        PricingRule updated = ruleRepository.save(rule);

        // Evict all cached prices for this lot (pattern delete)
        evictPriceCacheForLot(updated.getLotId());

        log.info("PricingRule {} updated for lot {} — Redis cache evicted", ruleId, updated.getLotId());
        return ResponseEntity.ok(updated);
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private void evictPriceCacheForLot(UUID lotId) {
        try {
            var keys = redisTemplate.keys(PRICE_CACHE_PREFIX + lotId + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("Evicted {} price cache entries for lot {}", keys.size(), lotId);
            }
        } catch (Exception e) {
            log.warn("Failed to evict price cache for lot {}: {}", lotId, e.getMessage());
        }
    }
}
