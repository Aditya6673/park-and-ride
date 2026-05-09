package com.parkride.pricing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkride.pricing.dto.PricingRuleRequest;
import com.parkride.pricing.repository.PricingRuleRepository;
import com.parkride.security.RsaKeyUtil;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link PricingController}.
 *
 * <p>Connects to the real Docker containers:
 * <ul>
 *   <li>{@code postgres-pricing} on port 5435 (via {@code application-test.properties})</li>
 *   <li>{@code redis} on port 6379</li>
 * </ul>
 *
 * <p>Start containers first:
 * <pre>
 *   docker-compose -f infrastructure/docker/docker-compose.yml up -d postgres-pricing redis
 * </pre>
 *
 * <p>Kafka is excluded — no Kafka infrastructure needed for these tests.
 *
 * <p>Tests run in declared order to build on shared state
 * (rule created in test 2 is used in tests 3, 4, 5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("PricingController — integration tests")
@SuppressWarnings("null")
class PricingControllerIT {

    /** RSA private key loaded once — used to sign test tokens matching the service's public key. */
    private static RSAPrivateKey TEST_PRIVATE_KEY;

    @BeforeAll
    static void loadTestKeys() throws java.io.IOException {
        TEST_PRIVATE_KEY = RsaKeyUtil.loadPrivateKey(new ClassPathResource("keys/private.pem").getInputStream());
    }

    @Autowired MockMvc          mockMvc;
    @Autowired ObjectMapper     objectMapper;
    @Autowired PricingRuleRepository ruleRepository;

    // Shared state across ordered tests
    static UUID   LOT_ID;
    static UUID   ADMIN_USER_ID;
    static UUID   RULE_ID;
    static String ADMIN_TOKEN;
    static String USER_TOKEN;

    @BeforeAll
    static void initSharedState() {
        LOT_ID       = UUID.randomUUID();
        ADMIN_USER_ID = UUID.randomUUID();
        ADMIN_TOKEN  = generateToken(ADMIN_USER_ID, "ROLE_ADMIN");
        USER_TOKEN   = generateToken(UUID.randomUUID(), "ROLE_USER");
    }

    @AfterAll
    static void cleanUp(@Autowired PricingRuleRepository repo) {
        if (RULE_ID != null) repo.deleteById(RULE_ID);
    }

    // ── Test 1: Price estimate with no rule → 404 ────────────────────────────

    @Test @Order(1)
    @DisplayName("GET /pricing/parking — 404 when no pricing rule exists for the lot")
    void getPriceEstimate_noRule_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/parking")
                        .param("lotId", LOT_ID.toString())
                        .param("durationMinutes", "60"))
                .andExpect(status().isNotFound());
    }

    // ── Test 2: Create pricing rule (ADMIN) ───────────────────────────────────

    @Test @Order(2)
    @DisplayName("POST /pricing/rules — ADMIN creates a rule (201 Created)")
    void createRule_asAdmin_createsRule() throws Exception {
        PricingRuleRequest request = new PricingRuleRequest();
        request.setLotId(LOT_ID);
        request.setBaseRate(new BigDecimal("50.00"));
        request.setLotCapacity(100);
        request.setPeakHoursStart(LocalTime.of(8, 0));
        request.setPeakHoursEnd(LocalTime.of(20, 0));
        request.setPeakMultiplier(new BigDecimal("1.50"));
        request.setOffPeakMultiplier(new BigDecimal("0.80"));
        request.setMaxSurgeCap(new BigDecimal("2.00"));

        String body = mockMvc.perform(post("/api/v1/pricing/rules")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.lotId").value(LOT_ID.toString()))
                .andExpect(jsonPath("$.baseRate").value(50.00))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Save ruleId for later tests
        RULE_ID = UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    // ── Test 3: Create rule (ROLE_USER) → 403 ────────────────────────────────

    @Test @Order(3)
    @DisplayName("POST /pricing/rules — ROLE_USER gets 403 Forbidden")
    void createRule_asUser_returns403() throws Exception {
        PricingRuleRequest request = new PricingRuleRequest();
        request.setLotId(UUID.randomUUID());
        request.setBaseRate(new BigDecimal("40.00"));
        request.setLotCapacity(50);
        request.setPeakHoursStart(LocalTime.of(9, 0));
        request.setPeakHoursEnd(LocalTime.of(18, 0));
        request.setPeakMultiplier(new BigDecimal("1.30"));
        request.setOffPeakMultiplier(new BigDecimal("0.90"));
        request.setMaxSurgeCap(new BigDecimal("1.50"));

        mockMvc.perform(post("/api/v1/pricing/rules")
                        .header("Authorization", "Bearer " + USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // ── Test 4: Price estimate after rule created (public endpoint) ───────────

    @Test @Order(4)
    @DisplayName("GET /pricing/parking — 200 with price breakdown after rule is created")
    void getPriceEstimate_withRule_returnsPriceBreakdown() throws Exception {
        // No JWT needed — public endpoint
        mockMvc.perform(get("/api/v1/pricing/parking")
                        .param("lotId", LOT_ID.toString())
                        .param("durationMinutes", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lotId").value(LOT_ID.toString()))
                .andExpect(jsonPath("$.durationMinutes").value(60))
                .andExpect(jsonPath("$.baseRatePerHour").value(50.00))
                .andExpect(jsonPath("$.totalPrice").isNumber())
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    // ── Test 5: Surge info (public endpoint) ─────────────────────────────────

    @Test @Order(5)
    @DisplayName("GET /pricing/surge/{lotId} — 200 with surge info (no Redis → 0 occupancy)")
    void getSurge_returnsInfo() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/surge/{lotId}", LOT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lotId").value(LOT_ID.toString()))
                .andExpect(jsonPath("$.currentOccupancy").value(0))
                .andExpect(jsonPath("$.lotCapacity").value(100))
                .andExpect(jsonPath("$.surgeMultiplier").value(1.0))
                .andExpect(jsonPath("$.occupancyLevel").value("LOW"));
    }

    // ── Test 6: Surge info for unknown lot → 404 ─────────────────────────────

    @Test @Order(6)
    @DisplayName("GET /pricing/surge/{lotId} — 404 for lot with no pricing rule")
    void getSurge_unknownLot_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/surge/{lotId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ── Test 7: Update rule (ADMIN) → cache evicted ───────────────────────────

    @Test @Order(7)
    @DisplayName("PUT /pricing/rules/{ruleId} — ADMIN updates rule, returns 200")
    void updateRule_asAdmin_updatesSuccessfully() throws Exception {
        PricingRuleRequest updated = new PricingRuleRequest();
        updated.setLotId(LOT_ID);
        updated.setBaseRate(new BigDecimal("60.00")); // price increase
        updated.setLotCapacity(120);
        updated.setPeakHoursStart(LocalTime.of(8, 0));
        updated.setPeakHoursEnd(LocalTime.of(21, 0));
        updated.setPeakMultiplier(new BigDecimal("1.75"));
        updated.setOffPeakMultiplier(new BigDecimal("0.75"));
        updated.setMaxSurgeCap(new BigDecimal("2.50"));

        mockMvc.perform(put("/api/v1/pricing/rules/{ruleId}", RULE_ID)
                        .header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseRate").value(60.00))
                .andExpect(jsonPath("$.lotCapacity").value(120))
                .andExpect(jsonPath("$.maxSurgeCap").value(2.50));
    }

    // ── Test 8: Update non-existent rule → 404 ────────────────────────────────

    @Test @Order(8)
    @DisplayName("PUT /pricing/rules/{ruleId} — 404 for non-existent ruleId")
    void updateRule_unknownId_returns404() throws Exception {
        PricingRuleRequest request = new PricingRuleRequest();
        request.setLotId(LOT_ID);
        request.setBaseRate(new BigDecimal("50.00"));
        request.setLotCapacity(100);
        request.setPeakHoursStart(LocalTime.of(8, 0));
        request.setPeakHoursEnd(LocalTime.of(20, 0));
        request.setPeakMultiplier(new BigDecimal("1.50"));
        request.setOffPeakMultiplier(new BigDecimal("0.80"));
        request.setMaxSurgeCap(new BigDecimal("2.00"));

        mockMvc.perform(put("/api/v1/pricing/rules/{ruleId}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    // ── Test 9: Price after rule update reflects new rates ────────────────────

    @Test @Order(9)
    @DisplayName("GET /pricing/parking — price reflects updated rule (base ₹60, cap 2.5×)")
    void getPriceEstimate_afterRuleUpdate_reflectsNewRate() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/parking")
                        .param("lotId", LOT_ID.toString())
                        .param("durationMinutes", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseRatePerHour").value(60.00))
                .andExpect(jsonPath("$.totalPrice").isNumber());
    }

    // ── Test 10: Validation error → 400 ──────────────────────────────────────

    @Test @Order(10)
    @DisplayName("POST /pricing/rules — invalid request (negative baseRate) returns 400")
    void createRule_invalidRequest_returns400() throws Exception {
        PricingRuleRequest bad = new PricingRuleRequest();
        bad.setLotId(LOT_ID);
        bad.setBaseRate(new BigDecimal("-10.00")); // invalid
        bad.setLotCapacity(100);
        bad.setPeakHoursStart(LocalTime.of(8, 0));
        bad.setPeakHoursEnd(LocalTime.of(20, 0));
        bad.setPeakMultiplier(new BigDecimal("1.50"));
        bad.setOffPeakMultiplier(new BigDecimal("0.80"));
        bad.setMaxSurgeCap(new BigDecimal("2.00"));

        mockMvc.perform(post("/api/v1/pricing/rules")
                        .header("Authorization", "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String generateToken(UUID userId, String role) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("userId",    userId.toString())
                .claim("email",     "test@parkride.com")
                .claim("roles",     role)
                .claim("tokenType", "ACCESS")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(TEST_PRIVATE_KEY)  // RS256 — matches the service's public key
                .compact();
    }
}
