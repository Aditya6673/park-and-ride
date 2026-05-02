package com.parkride.parking.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Broadcasts real-time slot availability updates to WebSocket subscribers.
 *
 * <p>Clients subscribe to: {@code /topic/availability/{lotId}}
 * <p>Message payload: {@code {"lotId":"...", "availableSlots": N}}
 *
 * <p>Called after every booking creation, cancellation, and no-show cancellation
 * so the frontend map updates without polling.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvailabilityBroadcastService {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcastAvailability(UUID lotId, long availableSlots) {
        String destination = "/topic/availability/" + lotId;
        AvailabilityMessage message = new AvailabilityMessage(lotId, availableSlots);
        messagingTemplate.convertAndSend(destination, message);
        log.debug("Broadcasted availability update: lot={} slots={}", lotId, availableSlots);
    }

    public record AvailabilityMessage(UUID lotId, long availableSlots) {}
}
