package com.parkride.auth.security;

import com.parkride.security.JwtUtil;
import com.parkride.security.SecurityConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Validates the JWT on every request and populates the {@link SecurityContextHolder}.
 *
 * <p>Filter order:
 * <ol>
 *   <li>Extract Bearer token from {@code Authorization} header</li>
 *   <li>Validate signature and expiry via {@link JwtUtil}</li>
 *   <li>Check the token's JTI against the Redis blacklist (logout)</li>
 *   <li>Reject refresh tokens on non-refresh endpoints</li>
 *   <li>Build {@link UsernamePasswordAuthenticationToken} and set in context</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BLACKLIST_KEY_PREFIX = "blacklist:";

    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(SecurityConstants.AUTH_HEADER);

        if (authHeader == null || !authHeader.startsWith(SecurityConstants.TOKEN_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(SecurityConstants.TOKEN_PREFIX.length());

        try {
            Claims claims = jwtUtil.validateAndExtractClaims(token);

            // Reject refresh tokens on all endpoints except /auth/refresh
            if (jwtUtil.isRefreshToken(claims) &&
                !request.getRequestURI().contains("/auth/refresh")) {
                log.warn("Refresh token used on non-refresh endpoint: {}", request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            // Check blacklist (logged-out tokens)
            String jti = jwtUtil.extractJti(claims);
            if (Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_KEY_PREFIX + jti))) {
                log.debug("Blacklisted token JTI {} rejected", jti);
                filterChain.doFilter(request, response);
                return;
            }

            // Build authentication object
            List<String> roles = jwtUtil.extractRoles(claims);
            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            jwtUtil.extractUserId(claims).toString(),
                            null,
                            authorities
                    );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (ExpiredJwtException e) {
            log.debug("JWT expired for request {}: {}", request.getRequestURI(), e.getMessage());
        } catch (JwtException e) {
            log.warn("JWT validation failed for request {}: {}", request.getRequestURI(), e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
