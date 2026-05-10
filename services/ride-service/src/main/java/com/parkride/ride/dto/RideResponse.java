package com.parkride.ride.dto;

import com.parkride.ride.domain.Ride;
import com.parkride.ride.domain.RideStatus;
import com.parkride.ride.domain.VehicleType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RideResponse(
        UUID id,
        UUID userId,
        UUID bookingId,
        UUID driverId,
        VehicleType vehicleType,
        RideStatus status,
        Double pickupLat,
        Double pickupLng,
        String pickupAddress,
        Double dropoffLat,
        Double dropoffLng,
        String dropoffAddress,
        BigDecimal estimatedFare,
        BigDecimal finalFare,
        Double distanceKm,
        Boolean isPooled,
        Integer passengerRating,
        Instant requestedAt,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt
) {
    public static RideResponse from(Ride r) {
        return new RideResponse(
                r.getId(), r.getUserId(), r.getBookingId(), r.getDriverId(),
                r.getVehicleType(), r.getStatus(),
                r.getPickupLat(), r.getPickupLng(), r.getPickupAddress(),
                r.getDropoffLat(), r.getDropoffLng(), r.getDropoffAddress(),
                r.getEstimatedFare(), r.getFinalFare(), r.getDistanceKm(),
                r.getIsPooled(), r.getPassengerRating(),
                r.getRequestedAt(), r.getStartedAt(), r.getCompletedAt(),
                r.getCreatedAt()
        );
    }
}
