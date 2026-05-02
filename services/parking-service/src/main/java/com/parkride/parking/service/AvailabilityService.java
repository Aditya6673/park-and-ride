package com.parkride.parking.service;

import com.parkride.parking.domain.SlotStatus;
import com.parkride.parking.repository.ParkingSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Manages the Redis availability cache for each parking lot.
 *
 * <p>Cache key: {@code availability:{lotId}} → available slot count (String).
 * TTL: 60 seconds — refreshed after every booking or cancellation.
 *
 * <p>Design rationale: reading slot availability from DB on every map load
 * would be O(N) SQL queries for N lots. Redis allows O(1) reads for the map
 * view, with the DB as the ground truth that refreshes the cache.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private static final String CACHE_PREFIX = "availability:";
    private static final Duration CACHE_TTL   = Duration.ofSeconds(60);

    private final RedisTemplate<String, String>  redisTemplate;
    private final ParkingSlotRepository          slotRepository;

    /**
     * Returns the available slot count for a lot.
     * Reads from Redis if cached; falls back to DB and re-populates the cache.
     */
    public long getAvailableCount(UUID lotId) {
        String key    = CACHE_PREFIX + lotId;
        String cached = redisTemplate.opsForValue().get(key);

        if (cached != null) {
            return Long.parseLong(cached);
        }

        long count = slotRepository.countByLotIdAndStatus(lotId, SlotStatus.AVAILABLE);
        redisTemplate.opsForValue().set(key, String.valueOf(count), CACHE_TTL);
        log.debug("Availability cache miss for lot {} — DB count: {}", lotId, count);
        return count;
    }

    /**
     * Invalidates and re-computes the cache for a lot.
     * Called after every booking creation or cancellation.
     */
    public void refreshCache(UUID lotId) {
        String key   = CACHE_PREFIX + lotId;
        long   count = slotRepository.countByLotIdAndStatus(lotId, SlotStatus.AVAILABLE);
        redisTemplate.opsForValue().set(key, String.valueOf(count), CACHE_TTL);
        log.debug("Availability cache refreshed for lot {} — count: {}", lotId, count);
    }

    /** Evicts a lot's cache entry. Used when a lot's slots are batch-modified. */
    public void evict(UUID lotId) {
        redisTemplate.delete(CACHE_PREFIX + lotId);
    }
}
