package com.parkride.ride.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class RideNotFoundException extends RuntimeException {
    public RideNotFoundException(UUID rideId) {
        super("Ride not found: " + rideId);
    }
}
