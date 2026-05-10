package com.parkride.ride.repository;

import com.parkride.ride.domain.Ride;
import com.parkride.ride.domain.RideStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RideRepository extends JpaRepository<Ride, UUID> {

    Page<Ride> findByUserId(UUID userId, Pageable pageable);

    List<Ride> findByDriverIdAndStatusIn(UUID driverId, List<RideStatus> statuses);

    Optional<Ride> findByBookingId(UUID bookingId);

    boolean existsByBookingIdAndStatusNot(UUID bookingId, RideStatus status);
}
