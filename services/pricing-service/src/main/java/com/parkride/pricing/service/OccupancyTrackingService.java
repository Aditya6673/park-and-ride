package com.parkride.pricing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Tracks real-time lot occupancy in Redis.
 *
 * <p>Occupancy is updated by {@link com.parkride.pricing.kafka.BookingEventConsumer}
 * on every {@code BOOKING_CONFIRMED} (increment) and {@code BOOKING_CANCELLED} /
 * {@code BOOKING_NO_SHOW} (decrement) event.
 *
 * <p><b>Redis key:</b> {@code occupancy:{lotId}} → integer string (e.g. {@code "12"}).
 *
 * <p><b>Known limitation:</b> The counter can drift after a service restart
 * because Kafka offsets are set to {@code latest}. Occupancy is best-effort
 * and self-corrects as new events flow through. A full reconstruction from
 * Kafka replay is planned for Phase F.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OccupancyTrackingService {

    private static final String KEY_PREFIX = "occupancy:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Increments the active booking count for the given lot.
     * Called on {@code BOOKING_CONFIRMED} events.
     */
    @SuppressWarnings("null")
    public void incrementOccupancy(UUID lotId) {
        Long newCount = redisTemplate.opsForValue().increment(key(lotId));
        log.debug("Occupancy++ for lot {}: now {}", lotId, newCount);
    }

    /**
     * Decrements the active booking count for the given lot.
     * Never goes below 0 to guard against event replay or counter drift.
     * Called on {@code BOOKING_CANCELLED} and {@code BOOKING_NO_SHOW} events.
     */
    @SuppressWarnings("null")
    public void decrementOccupancy(UUID lotId) {
        String k = key(lotId);
        Long newCount = redisTemplate.opsForValue().decrement(k);
        if (newCount != null && newCount < 0) {
            redisTemplate.opsForValue().set(k, "0");
            log.warn("Occupancy for lot {} went negative — reset to 0", lotId);
        } else {
            log.debug("Occupancy-- for lot {}: now {}", lotId, newCount);
        }
    }

    /**
     * Returns the current occupancy count (0 if key not found in Redis).
     */
    @SuppressWarnings("null")
    public int getOccupancy(UUID lotId) {
        String raw = redisTemplate.opsForValue().get(key(lotId));
        if (raw == null) return 0;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            log.warn("Corrupt occupancy value in Redis for lot {}: '{}' — defaulting to 0", lotId, raw);
            return 0;
        }
    }

    private String key(UUID lotId) {
        return KEY_PREFIX + lotId;
    }
}
