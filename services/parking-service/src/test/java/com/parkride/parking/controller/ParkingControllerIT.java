package com.parkride.parking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkride.parking.domain.*;
import com.parkride.parking.dto.CreateBookingRequest;
import com.parkride.parking.repository.BookingRepository;
import com.parkride.parking.repository.ParkingLotRepository;
import com.parkride.parking.repository.ParkingSlotRepository;
import com.parkride.parking.service.AvailabilityService;
import com.parkride.parking.service.QRCodeService;
import com.parkride.parking.websocket.AvailabilityBroadcastService;
import com.parkride.security.JwtUtil;
import org.junit.jupiter.api.*;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the Booking and ParkingLot controllers.
 *
 * <p>Uses {@code @SpringBootTest} with Testcontainers (via the {@code tc:} JDBC URL
 * in {@code application-test.properties}) so Flyway migrations run against a real PostgreSQL
 * container. Redis and Redisson are mocked to keep the test hermetic.
 *
 * <p>Covers:
 * <ul>
 *   <li>Full booking lifecycle: search → book → get → cancel</li>
 *   <li>Concurrent double-booking prevention</li>
 *   <li>QR image endpoint returning PNG bytes</li>
 *   <li>Unauthenticated access rejection</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Parking Controller — integration tests")
// "null" — Eclipse @NonNull false positives on Mockito stubs and Hibernate entity
//          getters (UUID, ParkingSlot) accessed through mock and Optional boundaries.
@SuppressWarnings("null")
class ParkingControllerIT {

    @Autowired MockMvc         mockMvc;
    @Autowired ObjectMapper    objectMapper;
    @Autowired JwtUtil         jwtUtil;
    @Autowired ParkingLotRepository  lotRepository;
    @Autowired ParkingSlotRepository slotRepository;
    @Autowired BookingRepository     bookingRepository;

    // Mock infrastructure not available in tests
    @MockitoBean AvailabilityService        availabilityService;
    @MockitoBean AvailabilityBroadcastService broadcastService;
    @MockitoBean RedissonClient             redissonClient;
    @MockitoBean RLock                      rLock;
    @MockitoBean QRCodeService              qrCodeService;
    @MockitoBean KafkaTemplate<String, Object> kafkaTemplate;

    // Shared state across ordered tests
    private static UUID          TEST_USER_ID;
    private static UUID          TEST_LOT_ID;
    private static UUID          TEST_SLOT_ID;
    private static UUID          TEST_BOOKING_ID;
    private static String        ACCESS_TOKEN;

    @BeforeAll
    static void initIds() {
        TEST_USER_ID = UUID.randomUUID();
    }

    @BeforeEach
    void setUpMocks() throws Exception {
        // Redisson mock — lock always acquires immediately
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        doNothing().when(rLock).unlock();

        // Redis availability mock
        when(availabilityService.getAvailableCount(any())).thenReturn(5L);
        doNothing().when(availabilityService).refreshCache(any());
        doNothing().when(broadcastService).broadcastAvailability(any(), anyLong());

        // QR mock
        when(qrCodeService.generateQrToken(any())).thenReturn("mocked-jwt-qr-token");
        when(qrCodeService.generateQrImage(any())).thenReturn(new byte[]{(byte)0x89, 0x50, 0x4E, 0x47}); // PNG magic bytes
    }

    // ── Test 1: Seed a lot and slot ───────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("GET /lots — returns seeded lots from V4 migration")
    void listLots_returnsSeededData() throws Exception {
        mockMvc.perform(get("/api/v1/parking/lots")
                        .param("city", "New Delhi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @Order(2)
    @DisplayName("Setup — create a test lot and slot in DB for remaining tests")
    void setupTestLotAndSlot() {
        ParkingLot testLot = ParkingLot.builder()
                .name("IT Test Lot")
                .address("Test Address")
                .city("TestCity")
                .latitude(28.63)
                .longitude(77.21)
                .totalSlots(5)
                .active(true)
                .build();
        testLot = lotRepository.save(testLot);
        TEST_LOT_ID = testLot.getId();

        ParkingSlot testSlot = ParkingSlot.builder()
                .lot(testLot)
                .slotNumber("IT-001")
                .slotType(SlotType.CAR)
                .status(SlotStatus.AVAILABLE)
                .pricePerHour(BigDecimal.valueOf(50))
                .floor("G")
                .positionIndex(1)
                .active(true)
                .build();
        testSlot = slotRepository.save(testSlot);
        TEST_SLOT_ID = testSlot.getId();

        // Generate a real JWT for the test user
        // We use a simple user object with the test UUID as principal
        ACCESS_TOKEN = generateTestToken(TEST_USER_ID, "ROLE_USER");

        assertThat(TEST_LOT_ID).isNotNull();
        assertThat(TEST_SLOT_ID).isNotNull();
        assertThat(ACCESS_TOKEN).isNotEmpty();
    }

    // ── Test 3: Get lot detail ─────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("GET /lots/{id} — returns lot details (public, no JWT required)")
    void getLot_public_success() throws Exception {
        mockMvc.perform(get("/api/v1/parking/lots/{id}", TEST_LOT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("IT Test Lot"))
                .andExpect(jsonPath("$.data.city").value("TestCity"));
    }

    @Test
    @Order(4)
    @DisplayName("GET /lots/{id}/slots — returns slots in lot (public)")
    void getSlots_public_success() throws Exception {
        mockMvc.perform(get("/api/v1/parking/lots/{id}/slots", TEST_LOT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].slotNumber").value("IT-001"));
    }

    // ── Test 5: Create booking ─────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("POST /bookings — creates CONFIRMED booking with QR token")
    void createBooking_authenticated_success() throws Exception {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setSlotId(TEST_SLOT_ID);
        request.setStartTime(Instant.now().plus(2, ChronoUnit.HOURS));
        request.setEndTime(Instant.now().plus(4, ChronoUnit.HOURS));

        String responseJson = mockMvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.qrToken").value("mocked-jwt-qr-token"))
                .andReturn().getResponse().getContentAsString();

        TEST_BOOKING_ID = UUID.fromString(
                objectMapper.readTree(responseJson).path("data").path("id").asText());
    }

    // ── Test 6: Reject unauthenticated ────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("POST /bookings — rejects request without JWT (401)")
    void createBooking_noJwt_unauthorized() throws Exception {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setSlotId(TEST_SLOT_ID);
        request.setStartTime(Instant.now().plus(2, ChronoUnit.HOURS));
        request.setEndTime(Instant.now().plus(4, ChronoUnit.HOURS));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // ── Test 7: Get booking ────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("GET /bookings/{id} — returns booking belonging to authenticated user")
    void getBooking_owner_success() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/{id}", TEST_BOOKING_ID)
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(TEST_BOOKING_ID.toString()));
    }

    @Test
    @Order(8)
    @DisplayName("GET /bookings/{id} — rejects different user accessing another user's booking (404)")
    void getBooking_wrongUser_notFound() throws Exception {
        String otherToken = generateTestToken(UUID.randomUUID(), "ROLE_USER");

        mockMvc.perform(get("/api/v1/bookings/{id}", TEST_BOOKING_ID)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    // ── Test 9: QR image ──────────────────────────────────────────────────

    @Test
    @Order(9)
    @DisplayName("GET /bookings/{id}/qr — returns PNG image bytes")
    void getQrImage_success() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/{id}/qr", TEST_BOOKING_ID)
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE));
    }

    // ── Test 10: Cancel booking ────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("DELETE /bookings/{id} — cancels booking and releases slot")
    void cancelBooking_owner_success() throws Exception {
        mockMvc.perform(delete("/api/v1/bookings/{id}", TEST_BOOKING_ID)
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Booking cancelled successfully"));

        Booking cancelled = bookingRepository.findById(TEST_BOOKING_ID).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    // ── Test 11: Concurrent double-booking ────────────────────────────────

    @Test
    @Order(11)
    @DisplayName("Concurrent booking — exactly one succeeds when two users book the same slot simultaneously")
    void concurrentBooking_exactlyOneSucceeds() throws Exception {
        // Reset slot to AVAILABLE
        ParkingSlot freshSlot = ParkingSlot.builder()
                .lot(lotRepository.findById(TEST_LOT_ID).orElseThrow())
                .slotNumber("IT-002")
                .slotType(SlotType.CAR)
                .status(SlotStatus.AVAILABLE)
                .pricePerHour(BigDecimal.valueOf(40))
                .floor("G")
                .positionIndex(2)
                .active(true)
                .build();
        freshSlot = slotRepository.save(freshSlot);
        final UUID freshSlotId = freshSlot.getId();

        CreateBookingRequest req = new CreateBookingRequest();
        req.setSlotId(freshSlotId);
        req.setStartTime(Instant.now().plus(5, ChronoUnit.HOURS));
        req.setEndTime(Instant.now().plus(7, ChronoUnit.HOURS));
        String reqJson = objectMapper.writeValueAsString(req);

        String token1 = generateTestToken(UUID.randomUUID(), "ROLE_USER");
        String token2 = generateTestToken(UUID.randomUUID(), "ROLE_USER");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        CountDownLatch startLatch  = new CountDownLatch(1);
        CountDownLatch doneLatch   = new CountDownLatch(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);

        for (String token : new String[]{token1, token2}) {
            pool.submit(() -> {
                try {
                    startLatch.await(); // fire simultaneously
                    int status = mockMvc.perform(post("/api/v1/bookings")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(reqJson))
                            .andReturn().getResponse().getStatus();

                    if (status == 201) successCount.incrementAndGet();
                    if (status == 409) conflictCount.incrementAndGet();
                } catch (Exception e) {
                    // count as failure
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release both threads simultaneously
        doneLatch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Exactly one booking confirmed, one rejected
        assertThat(successCount.get() + conflictCount.get()).isEqualTo(2);
        assertThat(successCount.get()).isEqualTo(1);

        // DB should have exactly one CONFIRMED booking for this slot
        long confirmed = bookingRepository.findAll().stream()
                .filter(b -> b.getSlot().getId().equals(freshSlotId))
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED)
                .count();
        assertThat(confirmed).isEqualTo(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String generateTestToken(UUID userId, String role) {
        // Build a token that exactly matches JwtUtil.buildToken() output:
        //   - roles stored as CSV string (not a List)
        //   - tokenType = "ACCESS" (required by JwtAuthFilter.isRefreshToken check)
        //   - userId claim present alongside sub
        return io.jsonwebtoken.Jwts.builder()
                .subject(userId.toString())
                .claim("userId",    userId.toString())
                .claim("email",     "test@parkride.com")
                .claim("roles",     role)               // CSV string, e.g. "ROLE_USER"
                .claim("tokenType", "ACCESS")
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-secret-minimum-32-characters-long".getBytes()))
                .compact();
    }
}
