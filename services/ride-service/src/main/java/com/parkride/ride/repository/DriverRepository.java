package com.parkride.ride.repository;

import com.parkride.ride.domain.Driver;
import com.parkride.ride.domain.DriverStatus;
import com.parkride.ride.domain.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverRepository extends JpaRepository<Driver, UUID> {

    Optional<Driver> findByUserId(UUID userId);

    /**
     * Finds all AVAILABLE drivers of the requested vehicle type whose last known
     * GPS position is within {@code radiusKm} kilometres of the pickup point.
     *
     * <p>Uses the Haversine approximation via PostgreSQL math functions.
     * For high-traffic scenarios this should be replaced with PostGIS ST_DWithin.
     */
    @Query("""
            SELECT d FROM Driver d
            WHERE d.status = :status
              AND d.vehicleType = :vehicleType
              AND d.currentLat IS NOT NULL
              AND d.currentLng IS NOT NULL
              AND (6371 * acos(
                    cos(radians(:lat)) * cos(radians(d.currentLat))
                    * cos(radians(d.currentLng) - radians(:lng))
                    + sin(radians(:lat)) * sin(radians(d.currentLat))
                  )) <= :radiusKm
            ORDER BY (6371 * acos(
                    cos(radians(:lat)) * cos(radians(d.currentLat))
                    * cos(radians(d.currentLng) - radians(:lng))
                    + sin(radians(:lat)) * sin(radians(d.currentLat))
                  )) ASC
            """)
    List<Driver> findNearbyAvailable(
            @Param("lat")         double lat,
            @Param("lng")         double lng,
            @Param("radiusKm")    double radiusKm,
            @Param("status")      DriverStatus status,
            @Param("vehicleType") VehicleType vehicleType
    );
}
