package com.parkride.pricing.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OccupancyTrackingService}.
 * Mocks {@link StringRedisTemplate} — no Redis container needed.
 */
class OccupancyTrackingServiceTest {

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    ValueOperations<String, String> valueOps;

    @InjectMocks
    OccupancyTrackingService service;

    UUID lotId;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        lotId = UUID.randomUUID();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ── incrementOccupancy ────────────────────────────────────────────────────

    @Test
    @DisplayName("incrementOccupancy → calls Redis increment with correct key")
    void incrementOccupancy_callsRedisIncrement() {
        when(valueOps.increment("occupancy:" + lotId)).thenReturn(5L);

        service.incrementOccupancy(lotId);

        verify(valueOps).increment("occupancy:" + lotId);
    }

    // ── decrementOccupancy ────────────────────────────────────────────────────

    @Test
    @DisplayName("decrementOccupancy with positive result → calls Redis decrement")
    @SuppressWarnings("null")
    void decrementOccupancy_positiveResult_callsRedisDecrement() {
        when(valueOps.decrement("occupancy:" + lotId)).thenReturn(3L);

        service.decrementOccupancy(lotId);

        verify(valueOps).decrement("occupancy:" + lotId);
        verify(valueOps, never()).set(anyString(), anyString());
    }

    @Test
    @DisplayName("decrementOccupancy with negative result → resets to 0")
    void decrementOccupancy_negativeResult_resetsToZero() {
        when(valueOps.decrement("occupancy:" + lotId)).thenReturn(-1L);

        service.decrementOccupancy(lotId);

        verify(valueOps).set("occupancy:" + lotId, "0");
    }

    @Test
    @DisplayName("decrementOccupancy with zero result → no reset needed")
    @SuppressWarnings("null")
    void decrementOccupancy_zeroResult_doesNotReset() {
        when(valueOps.decrement("occupancy:" + lotId)).thenReturn(0L);

        service.decrementOccupancy(lotId);

        verify(valueOps, never()).set(anyString(), anyString());
    }

    // ── getOccupancy ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getOccupancy → returns parsed integer from Redis")
    void getOccupancy_keyPresent_returnsParsedValue() {
        when(valueOps.get("occupancy:" + lotId)).thenReturn("12");

        int result = service.getOccupancy(lotId);

        assertThat(result).isEqualTo(12);
    }

    @Test
    @DisplayName("getOccupancy → returns 0 when key is absent")
    void getOccupancy_keyAbsent_returnsZero() {
        when(valueOps.get("occupancy:" + lotId)).thenReturn(null);

        int result = service.getOccupancy(lotId);

        assertThat(result).isZero();
    }

    @Test
    @DisplayName("getOccupancy → returns 0 and logs warning when value is corrupt")
    void getOccupancy_corruptValue_returnsZero() {
        when(valueOps.get("occupancy:" + lotId)).thenReturn("not-a-number");

        int result = service.getOccupancy(lotId);

        assertThat(result).isZero();
    }
}
