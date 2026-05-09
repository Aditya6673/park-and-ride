package com.parkride.gateway.config;

import com.parkride.security.JwtUtil;
import com.parkride.security.RsaKeyUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import reactor.core.publisher.Mono;

import java.io.IOException;

/**
 * Infrastructure beans for the API Gateway:
 * <ul>
 *   <li>{@link JwtUtil} — verify-only RS256 instance (no private key)</li>
 *   <li>{@link ReactiveRedisTemplate} — reactive template for rate limiter</li>
 *   <li>{@link KeyResolver} beans — keying strategy for rate limiting</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /** Verify-only — gateway never mints tokens, only validates them. */
    @Bean
    public JwtUtil jwtUtil(@Value("classpath:keys/public.pem") Resource publicKeyRes)
            throws IOException {
        return new JwtUtil(RsaKeyUtil.loadPublicKey(publicKeyRes.getInputStream()));
    }

    /** Reactive Redis template — required by Spring Cloud Gateway rate limiter. */
    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory factory) {
        return new ReactiveRedisTemplate<>(factory, RedisSerializationContext.string());
    }

    /**
     * Rate-limit key for authenticated requests — keyed by the userId injected
     * by {@code AuthenticationFilter} into the {@code X-User-Id} header.
     * Falls back to remote IP if the header is absent (e.g. auth-route).
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just(userId);
            }
            // Fallback to IP for unauthenticated routes
            var addr = exchange.getRequest().getRemoteAddress();
            return Mono.just(addr != null ? addr.getAddress().getHostAddress() : "unknown");
        };
    }

    /** Rate-limit key for unauthenticated routes (auth-route) — keyed by remote IP. */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            var addr = exchange.getRequest().getRemoteAddress();
            return Mono.just(addr != null ? addr.getAddress().getHostAddress() : "unknown");
        };
    }
}
