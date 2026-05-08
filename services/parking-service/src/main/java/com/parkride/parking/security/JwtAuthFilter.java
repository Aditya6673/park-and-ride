package com.parkride.parking.security;

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
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * JWT validation filter — identical in purpose to the one in auth-service,
 * but simplified: no Redis blacklist check (parking-service is stateless;
 * revocation is handled by auth-service before the JWT expires naturally).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

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

            // Reject refresh tokens on all endpoints
            if (jwtUtil.isRefreshToken(claims)) {
                filterChain.doFilter(request, response);
                return;
            }

            List<String> roles = jwtUtil.extractRoles(claims);
            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            jwtUtil.extractUserId(claims).toString(), null, authorities);
            // Store email in details so services can include it in Kafka events
            // without making a synchronous call to auth-service.
            authentication.setDetails(Map.of(
                    "email", jwtUtil.extractEmail(claims) != null ? jwtUtil.extractEmail(claims) : "",
                    "phone", jwtUtil.extractPhone(claims) != null ? jwtUtil.extractPhone(claims) : "",
                    "remoteAddr", request.getRemoteAddr()
            ));
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (ExpiredJwtException e) {
            log.debug("Expired JWT on {}: {}", request.getRequestURI(), e.getMessage());
        } catch (JwtException e) {
            log.warn("Invalid JWT on {}: {}", request.getRequestURI(), e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
