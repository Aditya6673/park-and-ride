package com.parkride.gateway.filter;

import com.parkride.security.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Gateway filter that validates the JWT on every route it is applied to.
 *
 * <p>On a <b>valid</b> token the filter:
 * <ol>
 *   <li>Strips the original {@code Authorization} header (prevents header spoofing)</li>
 *   <li>Injects {@code X-User-Id} and {@code X-User-Roles} headers for downstream use</li>
 *   <li>Forwards the request to the upstream service</li>
 * </ol>
 *
 * <p>On an <b>invalid or missing</b> token the filter short-circuits with
 * {@code 401 Unauthorized} and a {@code application/problem+json} body
 * — the upstream service is never reached.
 *
 * <p>Declared in {@code application.yml} per-route as {@code - AuthenticationFilter}.
 */
@Slf4j
@Component
public class AuthenticationFilter
        extends AbstractGatewayFilterFactory<AuthenticationFilter.Config> {

    private final JwtUtil jwtUtil;

    public AuthenticationFilter(JwtUtil jwtUtil) {
        super(Config.class);
        this.jwtUtil = jwtUtil;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getPath().value();

            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.debug("Missing or malformed Authorization header on path: {}", path);
                return unauthorized(exchange, "Missing or malformed Authorization header");
            }

            String token = authHeader.substring(7);

            Claims claims;
            try {
                claims = jwtUtil.validateAndExtractClaims(token);
            } catch (JwtException | IllegalArgumentException ex) {
                log.debug("JWT validation failed on path {}: {}", path, ex.getMessage());
                return unauthorized(exchange, "Invalid or expired token");
            }

            // Reject refresh tokens — only access tokens may be used at the gateway
            if (jwtUtil.isRefreshToken(claims)) {
                log.debug("Refresh token rejected at gateway on path: {}", path);
                return unauthorized(exchange, "Refresh tokens are not accepted here");
            }

            String userId = jwtUtil.extractUserId(claims).toString();
            String roles  = jwtUtil.extractRoles(claims)
                                   .stream()
                                   .reduce((a, b) -> a + "," + b)
                                   .orElse("");

            // Mutate request: remove Authorization, inject identity headers
            ServerHttpRequest mutated = request.mutate()
                    .headers(headers -> {
                        headers.remove(HttpHeaders.AUTHORIZATION);
                        headers.set("X-User-Id",    userId);
                        headers.set("X-User-Roles", roles);
                    })
                    .build();

            log.debug("JWT valid — userId={} roles={} path={}", userId, roles, path);
            return chain.filter(exchange.mutate().request(mutated).build());
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Mono<Void> unauthorized(ServerWebExchange exchange, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String body = """
                {"type":"about:blank","title":"Unauthorized","status":401,"detail":"%s"}
                """.formatted(detail).strip();

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /** No per-filter configuration needed — all config is in application.yml routes. */
    public static class Config {}
}
