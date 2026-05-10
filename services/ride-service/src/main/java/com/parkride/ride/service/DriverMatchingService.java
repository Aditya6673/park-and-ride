package com.parkride.ride.service;

import com.parkride.ride.domain.Driver;
import com.parkride.ride.domain.DriverStatus;
import com.parkride.ride.domain.VehicleType;
import com.parkride.ride.exception.NoDriverAvailableException;
import com.parkride.ride.repository.DriverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Finds the nearest available driver for a given vehicle type and pickup location.
 *
 * <p>Algorithm: Haversine-based SQL query within a configurable search radius.
 * Returns the closest driver. Expands radius if no match found in inner radius.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DriverMatchingService {

    private static final double INITIAL_RADIUS_KM  = 3.0;
    private static final double EXPANDED_RADIUS_KM = 8.0;

    private final DriverRepository driverRepository;

    /**
     * Finds the nearest available driver for the given vehicle type.
     *
     * @throws NoDriverAvailableException if no driver is found within the expanded radius
     */
    public Driver findNearest(double pickupLat, double pickupLng, VehicleType vehicleType) {
        // Try initial 3 km radius first
        List<Driver> candidates = driverRepository.findNearbyAvailable(
                pickupLat, pickupLng, INITIAL_RADIUS_KM,
                DriverStatus.AVAILABLE, vehicleType);

        if (!candidates.isEmpty()) {
            Driver matched = candidates.get(0);
            log.debug("Matched driver {} ({}) at {:.4f},{:.4f} for {} ride",
                    matched.getId(), matched.getName(),
                    matched.getCurrentLat(), matched.getCurrentLng(), vehicleType);
            return matched;
        }

        // Expand to 8 km
        log.debug("No {} driver within {}km, expanding to {}km",
                vehicleType, INITIAL_RADIUS_KM, EXPANDED_RADIUS_KM);

        candidates = driverRepository.findNearbyAvailable(
                pickupLat, pickupLng, EXPANDED_RADIUS_KM,
                DriverStatus.AVAILABLE, vehicleType);

        if (!candidates.isEmpty()) {
            return candidates.get(0);
        }

        throw new NoDriverAvailableException(vehicleType.name());
    }
}
