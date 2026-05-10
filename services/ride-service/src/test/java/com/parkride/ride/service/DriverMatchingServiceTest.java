package com.parkride.ride.service;

import com.parkride.ride.domain.Driver;
import com.parkride.ride.domain.DriverStatus;
import com.parkride.ride.domain.VehicleType;
import com.parkride.ride.exception.NoDriverAvailableException;
import com.parkride.ride.repository.DriverRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DriverMatchingServiceTest {

    @Mock DriverRepository driverRepository;

    @InjectMocks DriverMatchingService matchingService;

    private static final double LAT = 28.6139;
    private static final double LNG = 77.2090;

    @Test
    @DisplayName("findNearest — driver within 3km → returned immediately")
    void findNearest_withinInitialRadius() {
        Driver driver = buildDriver();
        when(driverRepository.findNearbyAvailable(eq(LAT), eq(LNG), eq(3.0),
                eq(DriverStatus.AVAILABLE), eq(VehicleType.CAB)))
                .thenReturn(List.of(driver));

        Driver result = matchingService.findNearest(LAT, LNG, VehicleType.CAB);

        assertThat(result.getId()).isEqualTo(driver.getId());
        // Expanded radius should NOT be called
        verify(driverRepository, times(1))
                .findNearbyAvailable(anyDouble(), anyDouble(), anyDouble(), any(), any());
    }

    @Test
    @DisplayName("findNearest — none in 3km but one in 8km → expanded radius used")
    void findNearest_expandedRadius() {
        Driver driver = buildDriver();
        when(driverRepository.findNearbyAvailable(eq(LAT), eq(LNG), eq(3.0),
                eq(DriverStatus.AVAILABLE), eq(VehicleType.CAB)))
                .thenReturn(Collections.emptyList());
        when(driverRepository.findNearbyAvailable(eq(LAT), eq(LNG), eq(8.0),
                eq(DriverStatus.AVAILABLE), eq(VehicleType.CAB)))
                .thenReturn(List.of(driver));

        Driver result = matchingService.findNearest(LAT, LNG, VehicleType.CAB);
        assertThat(result.getId()).isEqualTo(driver.getId());
        verify(driverRepository, times(2))
                .findNearbyAvailable(anyDouble(), anyDouble(), anyDouble(), any(), any());
    }

    @Test
    @DisplayName("findNearest — no driver in expanded radius → NoDriverAvailableException")
    void findNearest_noneAvailable() {
        when(driverRepository.findNearbyAvailable(anyDouble(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> matchingService.findNearest(LAT, LNG, VehicleType.SHUTTLE))
                .isInstanceOf(NoDriverAvailableException.class)
                .hasMessageContaining("SHUTTLE");
    }

    @Test
    @DisplayName("findNearest — SHUTTLE type respected in query")
    void findNearest_vehicleTypePassedThrough() {
        when(driverRepository.findNearbyAvailable(anyDouble(), anyDouble(), anyDouble(),
                eq(DriverStatus.AVAILABLE), eq(VehicleType.SHUTTLE)))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> matchingService.findNearest(LAT, LNG, VehicleType.SHUTTLE))
                .isInstanceOf(NoDriverAvailableException.class);

        verify(driverRepository, never())
                .findNearbyAvailable(anyDouble(), anyDouble(), anyDouble(),
                        any(), eq(VehicleType.CAB));
    }

    private Driver buildDriver() {
        return Driver.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("Test Driver")
                .vehicleType(VehicleType.CAB)
                .status(DriverStatus.AVAILABLE)
                .currentLat(28.615).currentLng(77.210)
                .rating(BigDecimal.valueOf(4.2))
                .totalRides(30)
                .build();
    }
}
