package com.parkride.gateway.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.parkride.security.RsaKeyUtil;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Integration tests for API Gateway routing and filter behaviour.
 *
 * <p><b>Strategy:</b>
 * <ul>
 *   <li>WireMock runs on a <b>fixed port 18080</b> — matching the route URIs
 *       declared in {@code application-test.properties}, avoiding the
 *       {@code @DynamicPropertySource} list-shadowing problem in Spring Boot.</li>
 *   <li>{@link MockitoBean} satisfies the {@code ReactiveRedisConnectionFactory}
 *       dependency (no real Redis needed — rate limiting excluded in test profile).</li>
 *   <li>JWT tokens are signed with the RSA private key from the test classpath.</li>
 * </ul>
 *
 * <p><b>Covers:</b>
 * <ul>
 *   <li>Auth route passes through without JWT (no filter on that route)</li>
 *   <li>Protected route blocked 401 when JWT is missing</li>
 *   <li>Protected route proxied with valid JWT; {@code X-User-Id} injected upstream</li>
 *   <li>{@code Authorization} header stripped before reaching upstream</li>
 *   <li>Expired JWT → 401</li>
 *   <li>Garbage token → 401</li>
 *   <li>{@code X-Trace-Id} present on every response (CorrelationIdFilter)</li>
 *   <li>Existing {@code X-Trace-Id} propagated unchanged</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Gateway Routing — integration tests")
class GatewayRoutingIT {

    // ── Fixed-port WireMock — must match application-test.properties URIs ────
    static final int WIREMOCK_PORT = 18080;

    static final WireMockServer wireMock =
            new WireMockServer(wireMockConfig().port(WIREMOCK_PORT));

    static {
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    // ── RSA private key for JWT generation ───────────────────────────────────
    static RSAPrivateKey privateKey;

    @BeforeAll
    static void loadKeys() throws IOException {
        privateKey = RsaKeyUtil.loadPrivateKey(
                new ClassPathResource("keys/private.pem").getInputStream());
    }

    // ── Mock the Redis factory so RedisConfig can create ReactiveRedisTemplate
    @MockitoBean
    ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    // ── WebTestClient (reactive, port auto-wired by Spring) ──────────────────
    @Autowired
    WebTestClient webTestClient;

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Auth route — no JWT required, request proxied to upstream")
    void authRoute_noJwt_proxied() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/auth/login"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accessToken\":\"dummy\"}")));

        webTestClient.post().uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.accessToken").isEqualTo("dummy");

        wireMock.verify(postRequestedFor(urlEqualTo("/api/v1/auth/login")));
    }

    @Test
    @Order(2)
    @DisplayName("Protected route — missing JWT → 401 Unauthorized")
    void parkingRoute_missingJwt_returns401() {
        webTestClient.get().uri("/api/v1/parking/slots")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json");

        wireMock.verify(0, getRequestedFor(urlEqualTo("/api/v1/parking/slots")));
    }

    @Test
    @Order(3)
    @DisplayName("Protected route — valid JWT → 200 proxied, X-User-Id injected upstream")
    void parkingRoute_validJwt_proxied() {
        UUID userId = UUID.randomUUID();
        String token = buildToken(userId, "ROLE_USER", false);

        wireMock.stubFor(get(urlEqualTo("/api/v1/parking/slots"))
                .withHeader("X-User-Id", equalTo(userId.toString()))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        webTestClient.get().uri("/api/v1/parking/slots")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        wireMock.verify(getRequestedFor(urlEqualTo("/api/v1/parking/slots"))
                .withHeader("X-User-Id", equalTo(userId.toString())));
    }

    @Test
    @Order(4)
    @DisplayName("Protected route — Authorization header stripped before proxying")
    void parkingRoute_authHeaderStrippedUpstream() {
        UUID userId = UUID.randomUUID();
        String token = buildToken(userId, "ROLE_USER", false);

        wireMock.stubFor(get(urlEqualTo("/api/v1/parking/slots"))
                .willReturn(aResponse().withStatus(200).withBody("[]")));

        webTestClient.get().uri("/api/v1/parking/slots")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        wireMock.verify(getRequestedFor(urlEqualTo("/api/v1/parking/slots"))
                .withoutHeader("Authorization"));
    }

    @Test
    @Order(5)
    @DisplayName("Protected route — expired JWT → 401 Unauthorized")
    void parkingRoute_expiredJwt_returns401() {
        String token = buildToken(UUID.randomUUID(), "ROLE_USER", true);

        webTestClient.get().uri("/api/v1/parking/slots")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized();

        wireMock.verify(0, getRequestedFor(urlEqualTo("/api/v1/parking/slots")));
    }

    @Test
    @Order(6)
    @DisplayName("Protected route — completely invalid token → 401 Unauthorized")
    void parkingRoute_invalidToken_returns401() {
        webTestClient.get().uri("/api/v1/parking/slots")
                .header("Authorization", "Bearer not.a.jwt.at.all")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @Order(7)
    @DisplayName("Every response carries X-Trace-Id (CorrelationIdFilter active)")
    void correlationId_presentInEveryResponse() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/auth/status"))
                .willReturn(aResponse().withStatus(200)));

        webTestClient.get().uri("/api/v1/auth/status")
                .exchange()
                .expectHeader().exists("X-Trace-Id");
    }

    @Test
    @Order(8)
    @DisplayName("Provided X-Trace-Id is propagated unchanged")
    void correlationId_propagatedWhenProvided() {
        String myTrace = "my-custom-trace-12345";

        wireMock.stubFor(get(urlEqualTo("/api/v1/auth/status"))
                .willReturn(aResponse().withStatus(200)));

        webTestClient.get().uri("/api/v1/auth/status")
                .header("X-Trace-Id", myTrace)
                .exchange()
                .expectHeader().valueEquals("X-Trace-Id", myTrace);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildToken(UUID userId, String role, boolean expired) {
        Instant now = Instant.now();
        Instant exp = expired
                ? now.minus(1, ChronoUnit.HOURS)
                : now.plus(1, ChronoUnit.HOURS);

        return Jwts.builder()
                .subject(userId.toString())
                .claim("userId", userId.toString())
                .claim("roles", role)
                .claim("tokenType", "ACCESS")
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }
}
