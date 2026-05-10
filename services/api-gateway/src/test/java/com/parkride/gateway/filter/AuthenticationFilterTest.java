package com.parkride.gateway.filter;

import com.parkride.security.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * Unit tests for {@link AuthenticationFilter}.
 *
 * <p>Uses {@link MockServerWebExchange} so no Spring context is needed.
 * The {@link JwtUtil} dependency is mocked to isolate filter behaviour
 * from actual RSA key loading.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // chain stub unused in 401-path tests
@DisplayName("AuthenticationFilter — unit tests")
@SuppressWarnings("null") // MockServerHttpRequest.get().build() lacks @NonNull in its API
class AuthenticationFilterTest {

    @Mock private JwtUtil       jwtUtil;
    @Mock private Claims        claims;
    @Mock private GatewayFilterChain chain;

    private AuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthenticationFilter(jwtUtil);
        // By default the chain just completes
        given(chain.filter(org.mockito.ArgumentMatchers.any())).willReturn(Mono.empty());
    }

    // ── Happy path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid JWT → X-User-Id and X-User-Roles injected, Authorization stripped")
    void validJwt_injectsHeaders() {
        UUID userId = UUID.randomUUID();
        given(jwtUtil.validateAndExtractClaims("valid.token")).willReturn(claims);
        given(jwtUtil.isRefreshToken(claims)).willReturn(false);
        given(jwtUtil.extractUserId(claims)).willReturn(userId);
        given(jwtUtil.extractRoles(claims)).willReturn(List.of("ROLE_USER"));

        var exchange = mockExchangeWithBearer("valid.token", "/api/v1/parking/slots");

        StepVerifier.create(filter.apply(new AuthenticationFilter.Config())
                        .filter(exchange, chain))
                .verifyComplete();

        // The chain was called with the mutated request — verify via capture
        var captor = org.mockito.ArgumentCaptor.forClass(
                org.springframework.web.server.ServerWebExchange.class);
        org.mockito.Mockito.verify(chain).filter(captor.capture());

        var mutatedRequest = captor.getValue().getRequest();
        assertThat(mutatedRequest.getHeaders().getFirst("X-User-Id"))
                .isEqualTo(userId.toString());
        assertThat(mutatedRequest.getHeaders().getFirst("X-User-Roles"))
                .isEqualTo("ROLE_USER");
        assertThat(mutatedRequest.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isNull();
    }

    // ── Missing / malformed header ────────────────────────────────────────

    @Test
    @DisplayName("Missing Authorization header → 401, chain not called")
    void missingAuthHeader_returns401() {
        var request  = MockServerHttpRequest.get("/api/v1/parking/slots").build();
        var exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.apply(new AuthenticationFilter.Config())
                        .filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        org.mockito.Mockito.verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("Authorization header without 'Bearer ' prefix → 401")
    void malformedAuthHeader_returns401() {
        var request  = MockServerHttpRequest.get("/api/v1/parking/slots")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                .build();
        var exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.apply(new AuthenticationFilter.Config())
                        .filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Invalid / expired token ────────────────────────────────────────────

    @Test
    @DisplayName("Expired token → 401, chain not called")
    void expiredJwt_returns401() {
        given(jwtUtil.validateAndExtractClaims(anyString()))
                .willThrow(new JwtException("JWT expired"));

        var exchange = mockExchangeWithBearer("expired.token", "/api/v1/parking/slots");

        StepVerifier.create(filter.apply(new AuthenticationFilter.Config())
                        .filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        org.mockito.Mockito.verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("Refresh token at gateway → 401")
    void refreshToken_returns401() {
        given(jwtUtil.validateAndExtractClaims(anyString())).willReturn(claims);
        given(jwtUtil.isRefreshToken(claims)).willReturn(true);

        var exchange = mockExchangeWithBearer("refresh.token", "/api/v1/parking/slots");

        StepVerifier.create(filter.apply(new AuthenticationFilter.Config())
                        .filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private MockServerWebExchange mockExchangeWithBearer(String token, String path) {
        var request = MockServerHttpRequest.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        return MockServerWebExchange.from(request);
    }
}
