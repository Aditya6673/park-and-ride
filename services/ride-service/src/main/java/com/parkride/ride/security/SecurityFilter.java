package com.parkride.ride.security;

import com.parkride.security.JwtUtil;
import com.parkride.security.SecurityConstants;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Security filter that trusts the gateway-injected {@code X-User-Id} and
 * {@code X-User-Roles} headers — no JWT re-parsing needed in this service.
 *
 * <p>Falls back to raw JWT validation if the headers are absent (direct
 * service-to-service calls or local dev without the gateway running).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SecurityFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String userId = request.getHeader(SecurityConstants.USER_ID_HEADER);
        String roles  = request.getHeader(SecurityConstants.USER_ROLES_HEADER);

        if (userId != null && !userId.isBlank()) {
            // Fast path — trust gateway-injected headers
            setAuthentication(userId, roles);
        } else {
            // Fallback — direct call with raw JWT (dev / integration test)
            String authHeader = request.getHeader(SecurityConstants.AUTH_HEADER);
            if (authHeader != null && authHeader.startsWith(SecurityConstants.TOKEN_PREFIX)) {
                String token = authHeader.substring(SecurityConstants.TOKEN_PREFIX.length());
                try {
                    Claims claims = jwtUtil.validateAndExtractClaims(token);
                    String subject = jwtUtil.extractUserId(claims).toString();
                    String rawRoles = String.join(",", jwtUtil.extractRoles(claims));
                    setAuthentication(subject, rawRoles);
                } catch (Exception ex) {
                    log.debug("JWT validation failed: {}", ex.getMessage());
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private void setAuthentication(String userId, String rolesHeader) {
        List<SimpleGrantedAuthority> authorities =
                (rolesHeader == null || rolesHeader.isBlank())
                        ? List.of()
                        : Arrays.stream(rolesHeader.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .map(SimpleGrantedAuthority::new)
                                .collect(Collectors.toList());

        var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
