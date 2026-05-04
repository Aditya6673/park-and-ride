package com.parkride.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkride.payment.dto.TopUpRequest;
import com.parkride.security.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Wallet REST endpoints.
 *
 * <p>Uses {@code @SpringBootTest} with the {@code test} profile which connects
 * to the running Docker container {@code postgres-payment} on port 5434.
 *
 * <p>Start containers first:
 * <pre>
 *   docker-compose -f infrastructure/docker/docker-compose.yml up -d postgres-payment redis
 * </pre>
 *
 * <p>Kafka is excluded via {@code spring.autoconfigure.exclude} in
 * {@code application-test.properties} — consumers and producers are mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("WalletController — integration tests")
@SuppressWarnings("null")
class WalletControllerIT {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtUtil      jwtUtil;

    // Kafka is auto-excluded by test profile, but mock required for context boot
    @MockitoBean KafkaTemplate<String, Object> kafkaTemplate;

    private static UUID   TEST_USER_ID;
    private static String ACCESS_TOKEN;

    @BeforeAll
    static void initIds() {
        TEST_USER_ID = UUID.randomUUID();
    }

    @BeforeEach
    void generateToken() {
        ACCESS_TOKEN = generateTestToken(TEST_USER_ID);
    }

    // ── Test 1: No wallet → 404 ───────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("GET /wallet — 404 when user has no wallet yet")
    void getWallet_noWallet_notFound() throws Exception {
        // Use a fresh userId that has no wallet
        String freshToken = generateTestToken(UUID.randomUUID());

        mockMvc.perform(get("/api/v1/payments/wallet")
                        .header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── Test 2: Unauthenticated → 401 ────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("GET /wallet — 401 without JWT")
    void getWallet_noJwt_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/payments/wallet"))
                .andExpect(status().isUnauthorized());
    }

    // ── Test 3: Top-up creates wallet ─────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("POST /wallet/topup — creates wallet if absent and adds balance")
    void topUp_createsWalletAndAddsBalance() throws Exception {
        TopUpRequest request = new TopUpRequest();
        setAmount(request, new BigDecimal("500.00"));

        mockMvc.perform(post("/api/v1/payments/wallet/topup")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.balance").value("500.00"))
                .andExpect(jsonPath("$.data.userId").value(TEST_USER_ID.toString()));
    }

    // ── Test 4: Wallet now accessible ────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("GET /wallet — returns wallet with correct balance after top-up")
    void getWallet_afterTopUp_returnsBalance() throws Exception {
        mockMvc.perform(get("/api/v1/payments/wallet")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.balance").value("500.00"));
    }

    // ── Test 5: Second top-up accumulates ────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("POST /wallet/topup — second top-up accumulates correctly")
    void topUp_secondTopUp_accumulatesBalance() throws Exception {
        TopUpRequest request = new TopUpRequest();
        setAmount(request, new BigDecimal("200.00"));

        mockMvc.perform(post("/api/v1/payments/wallet/topup")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value("700.00"));
    }

    // ── Test 6: Transactions list ─────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("GET /transactions — returns paginated transaction history")
    void getTransactions_returnsList() throws Exception {
        mockMvc.perform(get("/api/v1/payments/transactions")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray())
                // Two top-ups should have created 2 transactions
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    // ── Test 7: Invalid top-up ────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("POST /wallet/topup — rejects amount below ₹1.00 (422)")
    void topUp_invalidAmount_unprocessableEntity() throws Exception {
        TopUpRequest request = new TopUpRequest();
        setAmount(request, new BigDecimal("0.50"));

        mockMvc.perform(post("/api/v1/payments/wallet/topup")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String generateTestToken(UUID userId) {
        return io.jsonwebtoken.Jwts.builder()
                .subject(userId.toString())
                .claim("userId",    userId.toString())
                .claim("email",     "test@parkride.com")
                .claim("roles",     "ROLE_USER")
                .claim("tokenType", "ACCESS")
                .issuedAt(java.util.Date.from(Instant.now()))
                .expiration(java.util.Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-secret-minimum-32-characters-long".getBytes()))
                .compact();
    }

    /** Reflectively set the amount field (field is private, no setter). */
    private void setAmount(TopUpRequest request, BigDecimal amount)
            throws Exception {
        var field = TopUpRequest.class.getDeclaredField("amount");
        field.setAccessible(true);
        field.set(request, amount);
    }
}
