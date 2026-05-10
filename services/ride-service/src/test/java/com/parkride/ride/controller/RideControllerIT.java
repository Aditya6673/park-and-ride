package com.parkride.ride.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkride.ride.domain.*;
import com.parkride.ride.dto.*;
import com.parkride.ride.repository.DriverRepository;
import com.parkride.ride.repository.RideRepository;
import com.parkride.ride.service.DriverMatchingService;
import com.parkride.ride.service.RideEventPublisher;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — full Spring context with H2 in-memory DB.
 *
 * <p>Mocked infrastructure:
 * <ul>
 *   <li>{@link RideEventPublisher} — avoids KafkaTemplate generic-type wiring entirely</li>
 *   <li>{@link DriverMatchingService} — avoids Haversine JPQL on H2</li>
 *   <li>{@link RedisConnectionFactory} — avoids Lettuce connecting to unreachable Redis</li>
 * </ul>
 *
 * <p>Auth uses trusted gateway headers ({@code X-User-Id} / {@code X-User-Roles}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Ride Service — Controller Integration Tests")
@SuppressWarnings("null")
class RideControllerIT {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DriverRepository driverRepository;
    @Autowired RideRepository   rideRepository;

    @MockitoBean RideEventPublisher    rideEventPublisher;   // no-op Kafka publishing
    @MockitoBean DriverMatchingService driverMatchingService; // controlled matching
    @MockitoBean RedisConnectionFactory redisConnectionFactory; // no-op Redis

    private static UUID TEST_USER_ID;
    private static UUID TEST_ADMIN_ID;
    private static UUID OTHER_USER_ID;
    private static UUID SAVED_DRIVER_ID;
    private static UUID RIDE_A_ID;   // full happy-path lifecycle
    private static UUID RIDE_B_ID;   // cancel path

    @BeforeAll
    static void initIds() {
        TEST_USER_ID  = UUID.randomUUID();
        TEST_ADMIN_ID = UUID.randomUUID();
        OTHER_USER_ID = UUID.randomUUID();
    }

    // ══════════════════════════════════════════════════════════════════
    // 1. Seed
    // ══════════════════════════════════════════════════════════════════

    @Test @Order(1)
    @DisplayName("Setup — seed an AVAILABLE driver into the DB")
    void setup_seedDriver() {
        Driver driver = Driver.builder()
                .userId(UUID.randomUUID()).name("Raju Test").phone("+919876543210")
                .licenseNumber("DL-IT-001").vehicleType(VehicleType.CAB)
                .vehiclePlate("DL01AB1234").vehicleModel("Maruti Swift")
                .status(DriverStatus.AVAILABLE)
                .currentLat(28.6139).currentLng(77.2090)
                .rating(BigDecimal.valueOf(4.5)).totalRides(100)
                .build();
        SAVED_DRIVER_ID = driverRepository.save(driver).getId();
        assertThat(SAVED_DRIVER_ID).isNotNull();
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. Driver API
    // ══════════════════════════════════════════════════════════════════

    @Test @Order(2)
    @DisplayName("POST /drivers — non-admin → 403")
    void registerDriver_notAdmin_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/drivers")
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterDriverRequest(UUID.randomUUID(), "D", "+910000000000",
                                        "L1", VehicleType.SHUTTLE, "MH01", null))))
                .andExpect(status().isForbidden());
    }

    @Test @Order(3)
    @DisplayName("POST /drivers — admin → 201 Created")
    void registerDriver_admin_success() throws Exception {
        mockMvc.perform(post("/api/v1/drivers")
                        .header("X-User-Id", TEST_ADMIN_ID).header("X-User-Roles", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterDriverRequest(UUID.randomUUID(), "API Driver",
                                        "+911111111111", "LIC-API-001",
                                        VehicleType.ERICKSHAW, "KA05EF9999", "Bajaj RE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("API Driver"))
                .andExpect(jsonPath("$.data.status").value("OFFLINE"));
    }

    @Test @Order(4)
    @DisplayName("GET /drivers/{id} — returns driver")
    void getDriver_success() throws Exception {
        mockMvc.perform(get("/api/v1/drivers/{id}", SAVED_DRIVER_ID)
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Raju Test"))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"));
    }

    @Test @Order(5)
    @DisplayName("PUT /drivers/{id}/location — updates GPS")
    void updateDriverLocation_success() throws Exception {
        mockMvc.perform(put("/api/v1/drivers/{id}/location", SAVED_DRIVER_ID)
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateDriverLocationRequest(28.6304, 77.2177))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentLat").value(28.6304));
    }

    @Test @Order(6)
    @DisplayName("PUT /drivers/{id}/location — lat > 90 → 400")
    void updateDriverLocation_invalidCoords() throws Exception {
        mockMvc.perform(put("/api/v1/drivers/{id}/location", SAVED_DRIVER_ID)
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateDriverLocationRequest(200.0, 77.2177))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").exists());
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. Auth enforcement
    // ══════════════════════════════════════════════════════════════════

    @Test @Order(7)
    @DisplayName("POST /rides — no auth → 401")
    void requestRide_noAuth_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/rides")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRideReq())))
                .andExpect(status().isUnauthorized());
    }

    @Test @Order(8)
    @DisplayName("POST /rides — missing vehicleType → 400")
    void requestRide_missingField_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/rides")
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pickupLat":28.6,"pickupLng":77.2,"dropoffLat":28.63,"dropoffLng":77.21}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.vehicleType").exists());
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. Lifecycle A — driver available → full happy path
    // ══════════════════════════════════════════════════════════════════

    @Test @Order(9)
    @DisplayName("POST /rides — driver found → 201 DRIVER_ASSIGNED")
    void requestRide_driverAvailable_assigned() throws Exception {
        Driver d = driverRepository.findById(SAVED_DRIVER_ID).orElseThrow();
        when(driverMatchingService.findNearest(anyDouble(), anyDouble(), any())).thenReturn(d);

        String resp = mockMvc.perform(post("/api/v1/rides")
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRideReq())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRIVER_ASSIGNED"))
                .andExpect(jsonPath("$.data.estimatedFare").isNumber())
                .andReturn().getResponse().getContentAsString();

        RIDE_A_ID = UUID.fromString(
                objectMapper.readTree(resp).path("data").path("id").asText());

        verify(rideEventPublisher).publish(any(), any());
    }

    @Test @Order(10)
    @DisplayName("GET /rides/{id} — owner → 200")
    void getRide_owner_success() throws Exception {
        mockMvc.perform(get("/api/v1/rides/{id}", RIDE_A_ID)
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(RIDE_A_ID.toString()));
    }

    @Test @Order(11)
    @DisplayName("GET /rides/{id} — wrong user → 403")
    void getRide_wrongUser_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/rides/{id}", RIDE_A_ID)
                        .header("X-User-Id", OTHER_USER_ID).header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isForbidden());
    }

    @Test @Order(12)
    @DisplayName("POST /rides/{id}/arrived → DRIVER_ARRIVED")
    void driverArrived_success() throws Exception {
        mockMvc.perform(post("/api/v1/rides/{id}/arrived", RIDE_A_ID)
                        .header("X-User-Id", SAVED_DRIVER_ID).header("X-User-Roles", "ROLE_DRIVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRIVER_ARRIVED"));

        assertThat(rideRepository.findById(RIDE_A_ID).orElseThrow().getPickupAt()).isNotNull();
    }

    @Test @Order(13)
    @DisplayName("POST /rides/{id}/start → IN_PROGRESS")
    void startRide_success() throws Exception {
        mockMvc.perform(post("/api/v1/rides/{id}/start", RIDE_A_ID)
                        .header("X-User-Id", SAVED_DRIVER_ID).header("X-User-Roles", "ROLE_DRIVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test @Order(14)
    @DisplayName("POST /rides/{id}/complete → COMPLETED, driver AVAILABLE")
    void completeRide_success() throws Exception {
        mockMvc.perform(post("/api/v1/rides/{id}/complete", RIDE_A_ID)
                        .header("X-User-Id", SAVED_DRIVER_ID).header("X-User-Roles", "ROLE_DRIVER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CompleteRideRequest(BigDecimal.valueOf(150.00), 9.5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.finalFare").value(150.00))
                .andExpect(jsonPath("$.data.distanceKm").value(9.5));

        assertThat(driverRepository.findById(SAVED_DRIVER_ID).orElseThrow().getStatus())
                .isEqualTo(DriverStatus.AVAILABLE);
    }

    @Test @Order(15)
    @DisplayName("POST /rides/{id}/rate — rating 5 → persisted, driver avg updated")
    void rateRide_success() throws Exception {
        mockMvc.perform(post("/api/v1/rides/{id}/rate", RIDE_A_ID)
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RateRideRequest(5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passengerRating").value(5));

        assertThat(driverRepository.findById(SAVED_DRIVER_ID).orElseThrow().getRating())
                .isPositive();
    }

    @Test @Order(16)
    @DisplayName("POST /rides/{id}/rate — rating 0 → 400")
    void rateRide_outOfRange_badRequest() throws Exception {
        mockMvc.perform(post("/api/v1/rides/{id}/rate", RIDE_A_ID)
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RateRideRequest(0))))
                .andExpect(status().isBadRequest());
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. Lifecycle B — no driver → REQUESTED → cancel
    // ══════════════════════════════════════════════════════════════════

    @Test @Order(17)
    @DisplayName("POST /rides — no driver → 201 REQUESTED")
    void requestRide_noDriver_staysRequested() throws Exception {
        when(driverMatchingService.findNearest(anyDouble(), anyDouble(), any()))
                .thenThrow(new com.parkride.ride.exception.NoDriverAvailableException("CAB"));

        String resp = mockMvc.perform(post("/api/v1/rides")
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildRideReq())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();

        RIDE_B_ID = UUID.fromString(
                objectMapper.readTree(resp).path("data").path("id").asText());
    }

    @Test @Order(18)
    @DisplayName("GET /rides/my — paginated, ≥ 2 rides")
    void listMyRides_success() throws Exception {
        mockMvc.perform(get("/api/v1/rides/my")
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test @Order(19)
    @DisplayName("POST /rides/{id}/cancel — REQUESTED → CANCELLED")
    void cancelRide_requested_success() throws Exception {
        mockMvc.perform(post("/api/v1/rides/{id}/cancel", RIDE_B_ID)
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER")
                        .param("reason", "Changed my mind"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        Ride ride = rideRepository.findById(RIDE_B_ID).orElseThrow();
        assertThat(ride.getStatus()).isEqualTo(RideStatus.CANCELLED);
        assertThat(ride.getCancellationReason()).contains("Changed my mind");
    }

    @Test @Order(20)
    @DisplayName("POST /rides/{id}/cancel — COMPLETED ride → 409")
    void cancelRide_completed_conflict() throws Exception {
        mockMvc.perform(post("/api/v1/rides/{id}/cancel", RIDE_A_ID)
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isConflict());
    }

    @Test @Order(21)
    @DisplayName("GET /rides/{id} — non-existent → 404")
    void getRide_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/rides/{id}", UUID.randomUUID())
                        .header("X-User-Id", TEST_USER_ID).header("X-User-Roles", "ROLE_USER"))
                .andExpect(status().isNotFound());
    }

    // ══════════════════════════════════════════════════════════════════
    // 6. Concurrent requests
    // ══════════════════════════════════════════════════════════════════

    @Test @Order(22)
    @DisplayName("Concurrent ride requests — two different users both succeed (201)")
    void concurrentRideRequests_bothSucceed() throws Exception {
        Driver d = driverRepository.findById(SAVED_DRIVER_ID).orElseThrow();
        when(driverMatchingService.findNearest(anyDouble(), anyDouble(), any())).thenReturn(d);

        String body = objectMapper.writeValueAsString(buildRideReq());
        AtomicInteger created = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        for (UUID uid : new UUID[]{UUID.randomUUID(), UUID.randomUUID()}) {
            pool.submit(() -> {
                try {
                    start.await();
                    int status = mockMvc.perform(post("/api/v1/rides")
                                    .header("X-User-Id", uid).header("X-User-Roles", "ROLE_USER")
                                    .contentType(MediaType.APPLICATION_JSON).content(body))
                            .andReturn().getResponse().getStatus();
                    if (status == 201) created.incrementAndGet();
                } catch (Exception ignored) {
                } finally { done.countDown(); }
            });
        }
        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(created.get()).isEqualTo(2);
    }

    // ══════════════════════════════════════════════════════════════════
    // 7. Actuator
    // ══════════════════════════════════════════════════════════════════

    @Test @Order(23)
    @DisplayName("GET /actuator/health — public, UP")
    void actuatorHealth_public() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ══════════════════════════════════════════════════════════════════
    // 8. Repository guard — bookingId deduplication
    // ══════════════════════════════════════════════════════════════════

    @Test @Order(24)
    @DisplayName("existsByBookingIdAndStatusNot — cancelled ride does NOT block re-request")
    void bookingIdGuard_cancelledDoesNotBlock() {
        UUID bookingId = UUID.randomUUID();

        // No ride yet → false
        assertThat(rideRepository.existsByBookingIdAndStatusNot(
                bookingId, RideStatus.CANCELLED)).isFalse();

        // Save a CANCELLED ride
        rideRepository.save(Ride.builder()
                .userId(TEST_USER_ID).bookingId(bookingId)
                .vehicleType(VehicleType.CAB).status(RideStatus.CANCELLED)
                .pickupLat(28.6).pickupLng(77.2)
                .dropoffLat(28.63).dropoffLng(77.21)
                .build());

        // Still false — cancelled doesn't count
        assertThat(rideRepository.existsByBookingIdAndStatusNot(
                bookingId, RideStatus.CANCELLED)).isFalse();
    }

    // ── Helper ────────────────────────────────────────────────────────

    private RequestRideRequest buildRideReq() {
        return new RequestRideRequest(
                VehicleType.CAB,
                28.6139, 77.2090, "Parking Lot A",
                28.6304, 77.2177, "Connaught Place Metro",
                null, false);
    }
}
