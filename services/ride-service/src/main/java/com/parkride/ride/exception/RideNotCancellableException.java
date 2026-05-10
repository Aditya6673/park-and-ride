package com.parkride.ride.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.CONFLICT)
public class RideNotCancellableException extends RuntimeException {
    public RideNotCancellableException(UUID rideId) {
        super("Ride " + rideId + " cannot be cancelled in its current state");
    }
}
