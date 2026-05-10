package com.parkride.ride.service;

import com.parkride.events.RideEvent;
import com.parkride.ride.domain.*;
import com.parkride.ride.dto.*;
import com.parkride.ride.exception.*;
import com.parkride.ride.repository.RideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("null") // Mockito stubs lack @NonNull on return values
class RideServiceTest {

    @Mock RideRepository        rideRepository;
    @Mock DriverMatchingService driverMatchingService;
    @Mock DriverService         driverService;
    @Mock RideEventPublisher    eventPublisher;

    @InjectMocks RideService rideService;

    private UUID userId;
    private RequestRideRequest validRequest;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        validRequest = new RequestRideRequest(
                VehicleType.CAB,
                28.6139, 77.2090, "Parking Lot A",
                28.6304, 77.2177, "Connaught Place",
                null, false
        );

        when(rideRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rideRepository.existsByBookingIdAndStatusNot(any(), any())).thenReturn(false);
    }

    @Test
    @DisplayName("requestRide — no driver available → stays REQUESTED, publishes RIDE_REQUESTED")
    void requestRide_noDriverAvailable() {
        when(driverMatchingService.findNearest(anyDouble(), anyDouble(), any()))
                .thenThrow(new NoDriverAvailableException("CAB"));

        RideResponse response = rideService.requestRide(userId, validRequest);

        assertThat(response.status()).isEqualTo(RideStatus.REQUESTED);
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.vehicleType()).isEqualTo(VehicleType.CAB);
        assertThat(response.estimatedFare()).isPositive();

        verify(eventPublisher).publish(any(), eq(RideEvent.EventType.RIDE_REQUESTED));
    }

    @Test
    @DisplayName("requestRide — driver available → DRIVER_ASSIGNED, publishes RIDE_CONFIRMED")
    void requestRide_driverAssigned() {
        Driver driver = buildDriver();
        when(driverMatchingService.findNearest(anyDouble(), anyDouble(), any())).thenReturn(driver);

        RideResponse response = rideService.requestRide(userId, validRequest);

        assertThat(response.status()).isEqualTo(RideStatus.DRIVER_ASSIGNED);
        assertThat(response.driverId()).isEqualTo(driver.getId());
        verify(eventPublisher).publish(any(), eq(RideEvent.EventType.RIDE_CONFIRMED));
    }

    @Test
    @DisplayName("cancelRide — cancellable ride → CANCELLED, publishes RIDE_CANCELLED")
    void cancelRide_success() {
        Ride ride = buildRide(RideStatus.REQUESTED);
        when(rideRepository.findById(ride.getId())).thenReturn(Optional.of(ride));

        RideResponse response = rideService.cancelRide(ride.getId(), userId, "Changed plans");

        assertThat(response.status()).isEqualTo(RideStatus.CANCELLED);
        verify(eventPublisher).publish(any(), eq(RideEvent.EventType.RIDE_CANCELLED));
    }

    @Test
    @DisplayName("cancelRide — IN_PROGRESS ride → throws RideNotCancellableException")
    void cancelRide_notCancellable() {
        Ride ride = buildRide(RideStatus.IN_PROGRESS);
        when(rideRepository.findById(ride.getId())).thenReturn(Optional.of(ride));

        assertThatThrownBy(() -> rideService.cancelRide(ride.getId(), userId, null))
                .isInstanceOf(RideNotCancellableException.class);
        verify(eventPublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("completeRide — publishes RIDE_COMPLETED, sets finalFare and distanceKm")
    void completeRide_success() {
        Ride ride = buildRide(RideStatus.IN_PROGRESS);
        ride.assignDriver(UUID.randomUUID(), UUID.randomUUID());
        when(rideRepository.findById(ride.getId())).thenReturn(Optional.of(ride));
        when(driverService.findOrThrow(any())).thenReturn(buildDriver());

        CompleteRideRequest req = new CompleteRideRequest(BigDecimal.valueOf(120.00), 8.5);
        RideResponse response = rideService.completeRide(ride.getId(), req);

        assertThat(response.status()).isEqualTo(RideStatus.COMPLETED);
        assertThat(response.finalFare()).isEqualByComparingTo(BigDecimal.valueOf(120.00));
        assertThat(response.distanceKm()).isEqualTo(8.5);
        verify(eventPublisher).publish(any(), eq(RideEvent.EventType.RIDE_COMPLETED));
    }

    @Test
    @DisplayName("getById — wrong user → AccessDeniedException")
    void getById_wrongUser() {
        Ride ride = buildRide(RideStatus.REQUESTED);
        when(rideRepository.findById(ride.getId())).thenReturn(Optional.of(ride));

        UUID otherUser = UUID.randomUUID();
        assertThatThrownBy(() -> rideService.getById(ride.getId(), otherUser))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    @DisplayName("rateRide — valid completed ride → updates passengerRating")
    void rateRide_success() {
        Ride ride = buildRide(RideStatus.COMPLETED);
        when(rideRepository.findById(ride.getId())).thenReturn(Optional.of(ride));
        when(driverService.findOrThrow(any())).thenReturn(buildDriver());

        RideResponse response = rideService.rateRide(ride.getId(), userId, 5);
        assertThat(response.passengerRating()).isEqualTo(5);
    }

    @Test
    @DisplayName("haversineKm — same point → 0 km")
    void haversine_samePoint() {
        double km = RideService.haversineKm(28.6139, 77.2090, 28.6139, 77.2090);
        assertThat(km).isLessThan(0.001);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Ride buildRide(RideStatus status) {
        Ride ride = Ride.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .vehicleType(VehicleType.CAB)
                .status(status)
                .pickupLat(28.6139).pickupLng(77.2090)
                .dropoffLat(28.6304).dropoffLng(77.2177)
                .estimatedFare(BigDecimal.valueOf(80.00))
                .isPooled(false)
                .build();
        return ride;
    }

    private Driver buildDriver() {
        return Driver.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("Raju Driver")
                .vehicleType(VehicleType.CAB)
                .status(DriverStatus.AVAILABLE)
                .currentLat(28.614).currentLng(77.209)
                .rating(BigDecimal.valueOf(4.5))
                .totalRides(50)
                .build();
    }
}
