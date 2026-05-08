package com.parkride.pricing.repository;

import com.parkride.pricing.domain.PricingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PricingRule}.
 */
@Repository
public interface PricingRuleRepository extends JpaRepository<PricingRule, UUID> {

    /**
     * Finds the most recently effective active rule for the given lot.
     *
     * <p>A rule is "active" when:
     * <ul>
     *   <li>{@code effective_from <= at}</li>
     *   <li>{@code effective_to IS NULL OR effective_to > at}</li>
     * </ul>
     *
     * <p>If multiple rules match (overlapping ranges — should be prevented by the UI),
     * the one with the latest {@code effective_from} wins.
     */
    @Query("""
            SELECT r FROM PricingRule r
            WHERE r.lotId = :lotId
              AND r.effectiveFrom <= :at
              AND (r.effectiveTo IS NULL OR r.effectiveTo > :at)
            ORDER BY r.effectiveFrom DESC
            LIMIT 1
            """)
    Optional<PricingRule> findActiveRuleForLot(@Param("lotId") UUID lotId,
                                               @Param("at")    Instant at);
}
