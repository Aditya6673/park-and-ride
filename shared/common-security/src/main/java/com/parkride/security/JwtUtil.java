package com.parkride.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Stateless JWT utility — the single place where tokens are minted and verified.
 *
 * <p>Shared between:
 * <ul>
 *   <li>{@code auth-service} — mints access + refresh tokens on login/refresh</li>
 *   <li>{@code api-gateway} — validates the token on every inbound request
 *       before forwarding to downstream services</li>
 * </ul>
 *
 * <p>Both consumers must use the same secret and the same claim keys.
 * Those guarantees are enforced here — neither service contains JWT logic
 * of its own.
 *
 * <p>Instantiation: construct once per service and keep as a singleton
 * (Spring {@code @Bean} scope). The secret key derivation in the constructor
 * is expensive; doing it once per request would be wasteful.
 *
 * <pre>
 * {@code
 * // In auth-service SecurityConfig:
 * @Bean
 * public JwtUtil jwtUtil(@Value("${security.jwt.secret}") String secret) {
 *     return new JwtUtil(secret);
 * }
 * }
 * </pre>
 *
 * <p>Thread safety: all methods are stateless (no instance mutation after
 * construction). Safe for concurrent use across request threads.
 */
@Slf4j
public final class JwtUtil {

    /**
     * HMAC-SHA256 signing key derived from the application secret.
     * The secret must be at least 256 bits (32 characters) in length.
     * Validated in the constructor to fail fast at startup.
     */
    private final SecretKey signingKey;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * Constructs a {@code JwtUtil} instance from the provided secret string.
     *
     * @param secret the JWT signing secret — must be at minimum 32 characters.
     *               Injected from {@code security.jwt.secret} in application.yml.
     *               Never hardcode; always read from environment / secrets manager.
     * @throws IllegalArgumentException if the secret is too short
     */
    public JwtUtil(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 32 characters. " +
                            "Set 'security.jwt.secret' in application.yml or as an env variable."
            );
        }
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    // ── Token generation ──────────────────────────────────────────────────

    /**
     * Mints a short-lived access token for the given user.
     *
     * <p>Claims included:
     * <ul>
     *   <li>{@code sub} — the user's UUID (standard JWT subject)</li>
     *   <li>{@code userId} — same UUID, for convenient extraction without parsing {@code sub}</li>
     *   <li>{@code email} — user's email address</li>
     *   <li>{@code roles} — comma-separated role string: {@code "ROLE_USER,ROLE_ADMIN"}</li>
     *   <li>{@code tokenType} — {@code "ACCESS"}</li>
     *   <li>{@code jti} — unique token ID (used for blacklisting on logout)</li>
     * </ul>
     *
     * @param userId user's UUID (primary key from the users table)
     * @param email  user's email address
     * @param roles  list of Spring Security role names
     * @return signed JWT string
     */
    public String generateAccessToken(UUID userId, String email, List<String> roles) {
        return buildToken(userId, email, roles, SecurityConstants.TOKEN_TYPE_ACCESS,
                SecurityConstants.ACCESS_TOKEN_EXPIRY_MS);
    }

    /**
     * Mints a long-lived refresh token.
     *
     * <p>Refresh tokens carry only {@code sub}, {@code userId}, and {@code tokenType}.
     * They intentionally omit roles and email — a refresh token must never be
     * accepted by a resource endpoint, only by {@code POST /auth/refresh}.
     * The {@code tokenType=REFRESH} claim enforces this at the filter layer.
     *
     * @param userId user's UUID
     * @return signed refresh JWT string
     */
    public String generateRefreshToken(UUID userId) {
        return buildToken(userId, null, Collections.emptyList(),
                SecurityConstants.TOKEN_TYPE_REFRESH,
                SecurityConstants.REFRESH_TOKEN_EXPIRY_MS);
    }

    // ── Validation ────────────────────────────────────────────────────────

    /**
     * Validates a JWT string and returns the parsed claims.
     *
     * <p>Validation checks performed by JJWT:
     * <ol>
     *   <li>Signature integrity — tampered tokens are rejected immediately</li>
     *   <li>Expiry ({@code exp}) — expired tokens throw {@link ExpiredJwtException}</li>
     *   <li>Malformed structure — non-JWT strings throw {@link MalformedJwtException}</li>
     * </ol>
     *
     * <p>Callers should distinguish between expired tokens (which trigger a
     * refresh flow) and malformed/invalid tokens (which should result in HTTP 401).
     *
     * @param token the raw JWT string (without the {@code "Bearer "} prefix)
     * @return parsed {@link Claims}
     * @throws ExpiredJwtException   if the token's {@code exp} has passed
     * @throws JwtException          for all other validation failures
     */
    public Claims validateAndExtractClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Returns {@code true} if the token passes signature and expiry validation.
     * Swallows all exceptions — use this for boolean guard checks.
     * For access to the claims, use {@link #validateAndExtractClaims(String)} directly.
     *
     * @param token the raw JWT string
     */
    public boolean isTokenValid(String token) {
        try {
            validateAndExtractClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
        } catch (SignatureException e) {
            log.warn("JWT signature invalid — possible tamper attempt");
        } catch (MalformedJwtException e) {
            log.warn("JWT malformed: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("JWT algorithm unsupported: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
        }
        return false;
    }

    // ── Claims extraction helpers ─────────────────────────────────────────

    /**
     * Extracts the user UUID from the {@code userId} claim.
     *
     * @param claims pre-parsed claims from {@link #validateAndExtractClaims(String)}
     * @return the user's UUID
     * @throws IllegalArgumentException if the claim is absent or not a valid UUID
     */
    public UUID extractUserId(Claims claims) {
        String raw = claims.get(SecurityConstants.CLAIM_USER_ID, String.class);
        if (raw == null) {
            throw new IllegalArgumentException("JWT is missing required claim: userId");
        }
        return UUID.fromString(raw);
    }

    /**
     * Extracts the email address from the {@code email} claim.
     * Returns {@code null} for refresh tokens (which omit the email claim).
     */
    public String extractEmail(Claims claims) {
        return claims.get(SecurityConstants.CLAIM_EMAIL, String.class);
    }

    /**
     * Extracts the roles list from the {@code roles} claim.
     * Returns an empty list for refresh tokens.
     *
     * @param claims pre-parsed claims
     * @return immutable list of role strings (e.g. {@code ["ROLE_USER", "ROLE_ADMIN"]})
     */
    public List<String> extractRoles(Claims claims) {
        String raw = claims.get(SecurityConstants.CLAIM_ROLES, String.class);
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Extracts the token type ({@code "ACCESS"} or {@code "REFRESH"}).
     * Used by filters to reject refresh tokens on resource endpoints and
     * reject access tokens on the {@code /auth/refresh} endpoint.
     */
    public String extractTokenType(Claims claims) {
        return claims.get(SecurityConstants.CLAIM_TOKEN_TYPE, String.class);
    }

    /**
     * Extracts the unique token ID ({@code jti} claim).
     * Used by the token blacklist — when a user logs out, their token's
     * JTI is stored in Redis with a TTL matching the token's remaining validity.
     */
    public String extractJti(Claims claims) {
        return claims.getId();
    }

    /**
     * Returns {@code true} if the token's {@code tokenType} claim equals
     * {@link SecurityConstants#TOKEN_TYPE_ACCESS}.
     * Convenience wrapper over {@link #extractTokenType(Claims)}.
     */
    public boolean isAccessToken(Claims claims) {
        return SecurityConstants.TOKEN_TYPE_ACCESS.equals(extractTokenType(claims));
    }

    /**
     * Returns {@code true} if the token's {@code tokenType} claim equals
     * {@link SecurityConstants#TOKEN_TYPE_REFRESH}.
     */
    public boolean isRefreshToken(Claims claims) {
        return SecurityConstants.TOKEN_TYPE_REFRESH.equals(extractTokenType(claims));
    }

    // ── Private builder ───────────────────────────────────────────────────

    private String buildToken(UUID userId,
                              String email,
                              List<String> roles,
                              String tokenType,
                              long expiryMs) {

        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expiryMs);

        var builder = Jwts.builder()
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())       // jti — unique per token
                .issuedAt(now)
                .expiration(expiry)
                .claim(SecurityConstants.CLAIM_USER_ID,   userId.toString())
                .claim(SecurityConstants.CLAIM_TOKEN_TYPE, tokenType)
                .signWith(signingKey);

        // Only embed email and roles in access tokens
        if (email != null) {
            builder.claim(SecurityConstants.CLAIM_EMAIL, email);
        }
        if (!roles.isEmpty()) {
            builder.claim(SecurityConstants.CLAIM_ROLES, String.join(",", roles));
        }

        return builder.compact();
    }
}