package com.parkride.ride.service;

import com.parkride.events.RideEvent;
import com.parkride.ride.domain.Ride;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@SuppressWarnings("null") // Kafka API lacks @NonNull on KafkaTemplate.send() topic param
public class RideEventPublisher {

    static final String TOPIC = "ride-events";

    private final KafkaTemplate<String, RideEvent> kafkaTemplate;

    public void publish(Ride ride, RideEvent.EventType eventType) {
        RideEvent event = RideEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(eventType)
                .occurredAt(Instant.now())
                .rideId(ride.getId())
                .userId(ride.getUserId())
                .driverId(ride.getDriverId())
                .vehicleId(ride.getVehicleId())
                .vehicleType(ride.getVehicleType() != null ? ride.getVehicleType().name() : null)
                .status(ride.getStatus().name())
                .pickupLat(ride.getPickupLat())
                .pickupLng(ride.getPickupLng())
                .dropoffLat(ride.getDropoffLat())
                .dropoffLng(ride.getDropoffLng())
                .fare(ride.getFinalFare() != null ? ride.getFinalFare() : ride.getEstimatedFare())
                .distanceKm(ride.getDistanceKm())
                .isPooled(ride.getIsPooled())
                .poolGroupId(ride.getPoolGroupId())
                .build();

        kafkaTemplate.send(TOPIC, ride.getId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} for ride {}: {}",
                                eventType, ride.getId(), ex.getMessage());
                    } else {
                        log.debug("Published {} for ride {} to partition {}",
                                eventType, ride.getId(),
                                result.getRecordMetadata().partition());
                    }
                });
    }
}
