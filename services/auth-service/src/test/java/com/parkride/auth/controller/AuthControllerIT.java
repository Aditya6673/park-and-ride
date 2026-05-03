package com.parkride.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkride.auth.dto.LoginRequest;
import com.parkride.auth.dto.RegisterRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for AuthController.
 *
 * <p>Spins up a real PostgreSQL container and an in-memory Kafka broker.
 * Redis is mocked via the {@code application-test.yml} (store-type: none).
 * All Flyway migrations run against the real PostgreSQL container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"notification-events"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// "resource" — @Container manages the container lifecycle; no manual close needed.
// "null"     — MediaType constants and ObjectMapper.writeValueAsString() are never null;
//              Eclipse's @NonNull analysis produces false positives here.
@SuppressWarnings({"resource", "null"})
class AuthControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("auth_db")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Override the TC JDBC driver class for Testcontainers JUnit 5 lifecycle
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired MockMvc     mockMvc;
    @Autowired ObjectMapper objectMapper;

    private static final String EMAIL    = "integration@example.com";
    private static final String PASSWORD = "Integr@tion1!";

    // ── Registration ──────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /register: returns 201 with access token on valid input")
    void register_validInput_returns201() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email(EMAIL)
                .password(PASSWORD)
                .firstName("Integration")
                .lastName("Test")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value(EMAIL))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    @Order(2)
    @DisplayName("POST /register: returns 409 on duplicate email")
    void register_duplicateEmail_returns409() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email(EMAIL)      // already registered in @Order(1)
                .password(PASSWORD)
                .firstName("Dupe")
                .lastName("User")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @Order(3)
    @DisplayName("POST /register: returns 422 on missing required fields")
    void register_missingFields_returns422() throws Exception {
        // Empty body — all required fields missing
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations").isArray());
    }

    // ── Login ─────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("POST /login: returns 200 with access token on valid credentials")
    void login_validCredentials_returns200() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email(EMAIL)
                .password(PASSWORD)
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.email").value(EMAIL));
    }

    @Test
    @Order(5)
    @DisplayName("POST /login: returns 401 on wrong password")
    void login_wrongPassword_returns401() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email(EMAIL)
                .password("wrongpassword")
                .build();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(6)
    @DisplayName("POST /refresh: returns 401 when no refresh token provided")
    void refresh_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }
}
