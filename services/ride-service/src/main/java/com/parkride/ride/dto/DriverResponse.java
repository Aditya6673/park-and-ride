package com.parkride.ride.dto;

import com.parkride.ride.domain.Driver;
import com.parkride.ride.domain.DriverStatus;
import com.parkride.ride.domain.VehicleType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DriverResponse(
        UUID id,
        UUID userId,
        String name,
        String phone,
        VehicleType vehicleType,
        String vehiclePlate,
        String vehicleModel,
        DriverStatus status,
        Double currentLat,
        Double currentLng,
        BigDecimal rating,
        Integer totalRides,
        Instant createdAt
) {
    public static DriverResponse from(Driver d) {
        return new DriverResponse(
                d.getId(), d.getUserId(), d.getName(), d.getPhone(),
                d.getVehicleType(), d.getVehiclePlate(), d.getVehicleModel(),
                d.getStatus(), d.getCurrentLat(), d.getCurrentLng(),
                d.getRating(), d.getTotalRides(), d.getCreatedAt()
        );
    }
}
