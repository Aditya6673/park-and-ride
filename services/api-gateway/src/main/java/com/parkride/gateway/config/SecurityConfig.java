package com.parkride.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Disables Spring Security's default reactive filter chain.
 *
 * <p>The gateway uses its own {@code AuthenticationFilter} (a
 * {@link org.springframework.cloud.gateway.filter.GatewayFilterFactory})
 * to validate JWTs at the route level. Letting Spring Security run its
 * own auth chain in parallel would cause double-processing and
 * unexpected 401s on routes that Spring Security doesn't know about.
 *
 * <p>All access control is enforced by:
 * <ol>
 *   <li>{@code AuthenticationFilter} — validates the JWT and injects
 *       {@code X-User-Id} / {@code X-User-Roles} headers</li>
 *   <li>Downstream services — re-validate or trust the injected headers</li>
 * </ol>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }
}
