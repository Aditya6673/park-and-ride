package com.parkride.ride.service;

import com.parkride.events.RideEvent;
import com.parkride.ride.domain.Driver;
import com.parkride.ride.domain.Ride;
import com.parkride.ride.domain.RideStatus;
import com.parkride.ride.domain.VehicleType;
import com.parkride.ride.dto.*;
import com.parkride.ride.exception.RideNotCancellableException;
import com.parkride.ride.exception.RideNotFoundException;
import com.parkride.ride.repository.RideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@SuppressWarnings("null") // JPA API lacks @NonNull on save()/findById() returns
public class RideService {

    private final RideRepository        rideRepository;
    private final DriverMatchingService driverMatchingService;
    private final DriverService         driverService;
    private final RideEventPublisher    eventPublisher;

    // ── Passenger operations ──────────────────────────────────────────────────

    @Transactional
    public RideResponse requestRide(UUID userId, RequestRideRequest req) {
        // Estimated fare — flat rate for now; Pricing Service integration in next phase
        BigDecimal estimatedFare = estimateFare(req.vehicleType(),
                req.pickupLat(), req.pickupLng(),
                req.dropoffLat(), req.dropoffLng());

        Ride ride = Ride.builder()
                .userId(userId)
                .bookingId(req.bookingId())
                .vehicleType(req.vehicleType())
                .pickupLat(req.pickupLat())
                .pickupLng(req.pickupLng())
                .pickupAddress(req.pickupAddress())
                .dropoffLat(req.dropoffLat())
                .dropoffLng(req.dropoffLng())
                .dropoffAddress(req.dropoffAddress())
                .estimatedFare(estimatedFare)
                .isPooled(Boolean.TRUE.equals(req.pooled()))
                .build();

        ride = rideRepository.save(ride);
        log.info("Ride {} requested by user {} ({} → {})",
                ride.getId(), userId, req.vehicleType(), req.dropoffAddress());

        // Attempt immediate driver assignment
        try {
            Driver driver = driverMatchingService.findNearest(
                    req.pickupLat(), req.pickupLng(), req.vehicleType());
            assignDriverInternal(ride, driver);
        } catch (Exception ex) {
            // No driver available — ride stays REQUESTED, retry via scheduler
            log.info("No driver available immediately for ride {}: {}", ride.getId(), ex.getMessage());
            eventPublisher.publish(ride, RideEvent.EventType.RIDE_REQUESTED);
        }

        return RideResponse.from(rideRepository.save(ride));
    }

    @Transactional(readOnly = true)
    public RideResponse getById(UUID rideId, UUID requestingUserId) {
        Ride ride = findOrThrow(rideId);
        if (!ride.isOwnedBy(requestingUserId)) {
            throw new AccessDeniedException("You do not own this ride");
        }
        return RideResponse.from(ride);
    }

    @Transactional(readOnly = true)
    public Page<RideResponse> listMyRides(UUID userId, Pageable pageable) {
        return rideRepository.findByUserId(userId, pageable).map(RideResponse::from);
    }

    @Transactional
    public RideResponse cancelRide(UUID rideId, UUID userId, String reason) {
        Ride ride = findOrThrow(rideId);
        if (!ride.isOwnedBy(userId)) {
            throw new AccessDeniedException("You do not own this ride");
        }
        if (!ride.isCancellable()) {
            throw new RideNotCancellableException(rideId);
        }

        // Release driver if already assigned
        if (ride.getDriverId() != null) {
            Driver driver = driverService.findOrThrow(ride.getDriverId());
            driver.finishRide();   // back to AVAILABLE
        }

        ride.cancel(reason != null ? reason : "Cancelled by passenger");
        ride = rideRepository.save(ride);
        eventPublisher.publish(ride, RideEvent.EventType.RIDE_CANCELLED);
        log.info("Ride {} cancelled by user {}", rideId, userId);
        return RideResponse.from(ride);
    }

    @Transactional
    public RideResponse rateRide(UUID rideId, UUID userId, int rating) {
        Ride ride = findOrThrow(rideId);
        if (!ride.isOwnedBy(userId)) {
            throw new AccessDeniedException("You do not own this ride");
        }
        if (ride.getStatus() != RideStatus.COMPLETED) {
            throw new IllegalStateException("Can only rate completed rides");
        }
        ride.rate(rating);

        // Update driver running average
        if (ride.getDriverId() != null) {
            Driver driver = driverService.findOrThrow(ride.getDriverId());
            driver.addRating(BigDecimal.valueOf(rating));
        }

        return RideResponse.from(rideRepository.save(ride));
    }

    // ── Driver operations ─────────────────────────────────────────────────────

    @Transactional
    public RideResponse markDriverArrived(UUID rideId) {
        Ride ride = findOrThrow(rideId);
        ride.markDriverArrived();
        ride = rideRepository.save(ride);
        eventPublisher.publish(ride, RideEvent.EventType.DRIVER_ARRIVED);
        return RideResponse.from(ride);
    }

    @Transactional
    public RideResponse startRide(UUID rideId) {
        Ride ride = findOrThrow(rideId);
        ride.start();
        ride = rideRepository.save(ride);
        eventPublisher.publish(ride, RideEvent.EventType.RIDE_STARTED);
        return RideResponse.from(ride);
    }

    @Transactional
    public RideResponse completeRide(UUID rideId, CompleteRideRequest req) {
        Ride ride = findOrThrow(rideId);
        ride.complete(req.finalFare(), req.distanceKm());

        // Release driver
        if (ride.getDriverId() != null) {
            Driver driver = driverService.findOrThrow(ride.getDriverId());
            driver.finishRide();
        }

        ride = rideRepository.save(ride);
        eventPublisher.publish(ride, RideEvent.EventType.RIDE_COMPLETED);
        log.info("Ride {} completed. Fare: {} Distance: {}km",
                rideId, req.finalFare(), req.distanceKm());
        return RideResponse.from(ride);
    }

    // ── Package-private: used by BookingEventConsumer ────────────────────────

    @Transactional
    public RideResponse autoRequestFromBooking(UUID userId, UUID bookingId,
                                               double pickupLat, double pickupLng,
                                               double dropoffLat, double dropoffLng,
                                               VehicleType vehicleType) {
        // Skip if a ride already exists for this booking
        if (rideRepository.existsByBookingIdAndStatusNot(bookingId, RideStatus.CANCELLED)) {
            log.debug("Ride already exists for booking {}, skipping auto-request", bookingId);
            return null;
        }
        RequestRideRequest req = new RequestRideRequest(
                vehicleType, pickupLat, pickupLng, null,
                dropoffLat, dropoffLng, null, bookingId, false);
        return requestRide(userId, req);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void assignDriverInternal(Ride ride, Driver driver) {
        ride.assignDriver(driver.getId(), driver.getId()); // vehicleId = driverId for now
        driver.startRide();
        eventPublisher.publish(ride, RideEvent.EventType.RIDE_CONFIRMED);
        log.info("Driver {} assigned to ride {}", driver.getId(), ride.getId());
    }

    private Ride findOrThrow(UUID rideId) {
        return rideRepository.findById(rideId)
                .orElseThrow(() -> new RideNotFoundException(rideId));
    }

    /**
     * Flat-rate fare estimate. Replace with Pricing Service call in next phase.
     * CAB: ₹15/km base, SHUTTLE: ₹8/km, ERICKSHAW: ₹10/km.
     */
    private BigDecimal estimateFare(VehicleType type, double fromLat, double fromLng,
                                    double toLat, double toLng) {
        double distanceKm = haversineKm(fromLat, fromLng, toLat, toLng);
        double rate = switch (type) {
            case CAB       -> 15.0;
            case SHUTTLE   -> 8.0;
            case ERICKSHAW -> 10.0;
        };
        return BigDecimal.valueOf(Math.max(30.0, distanceKm * rate))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Haversine formula — straight-line distance in km between two WGS84 points. */
    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
