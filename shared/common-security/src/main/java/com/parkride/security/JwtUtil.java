package com.parkride.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Stateless JWT utility using <b>RS256</b> (RSA + SHA-256, asymmetric).
 *
 * <p>Design:
 * <ul>
 *   <li>{@code auth-service} — holds the <em>private key</em>, mints tokens via
 *       {@link #JwtUtil(RSAPrivateKey, RSAPublicKey)}</li>
 *   <li>All other services (gateway, parking, payment, pricing, …) — hold only the
 *       <em>public key</em> and verify tokens via {@link #JwtUtil(RSAPublicKey)}</li>
 * </ul>
 *
 * <p>This separation means a compromised downstream service cannot forge tokens —
 * it only has the public key, which is safe to distribute freely.
 *
 * <p>Key loading (Spring Bean example):
 * <pre>{@code
 * // auth-service — can sign AND verify
 * @Bean
 * public JwtUtil jwtUtil(
 *         @Value("classpath:keys/private.pem") Resource privateKeyRes,
 *         @Value("classpath:keys/public.pem")  Resource publicKeyRes) throws Exception {
 *     return new JwtUtil(RsaKeyUtil.loadPrivateKey(privateKeyRes),
 *                        RsaKeyUtil.loadPublicKey(publicKeyRes));
 * }
 *
 * // other services — verify only
 * @Bean
 * public JwtUtil jwtUtil(
 *         @Value("classpath:keys/public.pem") Resource publicKeyRes) throws Exception {
 *     return new JwtUtil(RsaKeyUtil.loadPublicKey(publicKeyRes));
 * }
 * }</pre>
 *
 * <p>Thread safety: all methods are stateless after construction. Safe for concurrent use.
 */
@Slf4j
public final class JwtUtil {

    /** RSA private key — present only in auth-service. {@code null} in all other services. */
    private final RSAPrivateKey privateKey;

    /** RSA public key — present in every service for token verification. */
    private final RSAPublicKey publicKey;

    // ── Constructors ──────────────────────────────────────────────────────

    /**
     * Verify-only constructor — for all services <em>except</em> auth-service.
     *
     * @param publicKey the RSA public key loaded from {@code classpath:keys/public.pem}
     */
    public JwtUtil(RSAPublicKey publicKey) {
        this.publicKey  = publicKey;
        this.privateKey = null;
    }

    /**
     * Sign + verify constructor — for auth-service only.
     *
     * @param privateKey the RSA private key loaded from {@code classpath:keys/private.pem}
     * @param publicKey  the RSA public key loaded from {@code classpath:keys/public.pem}
     */
    public JwtUtil(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey  = publicKey;
    }

    // ── Token generation ──────────────────────────────────────────────────

    /**
     * Mints a short-lived RS256 access token for the given user.
     *
     * <p>Claims included:
     * <ul>
     *   <li>{@code sub} — the user's UUID</li>
     *   <li>{@code userId} — same UUID, for convenient extraction</li>
     *   <li>{@code email} — user's email address</li>
     *   <li>{@code phone} — user's phone number (for SMS notifications)</li>
     *   <li>{@code roles} — comma-separated: {@code "ROLE_USER,ROLE_ADMIN"}</li>
     *   <li>{@code tokenType} — {@code "ACCESS"}</li>
     *   <li>{@code jti} — unique token ID (for blacklisting on logout)</li>
     * </ul>
     *
     * @throws IllegalStateException if called on a verify-only instance (no private key)
     */
    public String generateAccessToken(UUID userId, String email, String phone, List<String> roles) {
        return buildToken(userId, email, phone, roles,
                SecurityConstants.TOKEN_TYPE_ACCESS,
                SecurityConstants.ACCESS_TOKEN_EXPIRY_MS);
    }

    /**
     * Mints a long-lived RS256 refresh token.
     * Refresh tokens carry only {@code sub}, {@code userId}, and {@code tokenType}.
     *
     * @throws IllegalStateException if called on a verify-only instance (no private key)
     */
    public String generateRefreshToken(UUID userId) {
        return buildToken(userId, null, null, Collections.emptyList(),
                SecurityConstants.TOKEN_TYPE_REFRESH,
                SecurityConstants.REFRESH_TOKEN_EXPIRY_MS);
    }

    // ── Validation ────────────────────────────────────────────────────────

    /**
     * Validates an RS256 JWT and returns the parsed claims.
     *
     * @param token the raw JWT string (without {@code "Bearer "} prefix)
     * @return parsed {@link Claims}
     * @throws ExpiredJwtException if the token's {@code exp} has passed
     * @throws JwtException        for all other validation failures
     */
    public Claims validateAndExtractClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Returns {@code true} if the token passes RS256 signature and expiry validation.
     * Swallows all exceptions — use for boolean guard checks only.
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

    /** Extracts the user UUID from the {@code userId} claim. */
    public UUID extractUserId(Claims claims) {
        String raw = claims.get(SecurityConstants.CLAIM_USER_ID, String.class);
        if (raw == null) {
            throw new IllegalArgumentException("JWT is missing required claim: userId");
        }
        return UUID.fromString(raw);
    }

    /** Extracts the email from the {@code email} claim. {@code null} for refresh tokens. */
    public String extractEmail(Claims claims) {
        return claims.get(SecurityConstants.CLAIM_EMAIL, String.class);
    }

    /** Extracts the phone from the {@code phone} claim. {@code null} when absent. */
    public String extractPhone(Claims claims) {
        return claims.get(SecurityConstants.CLAIM_PHONE, String.class);
    }

    /**
     * Extracts the roles list from the {@code roles} claim.
     * Returns an empty list for refresh tokens.
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

    /** Extracts the token type ({@code "ACCESS"} or {@code "REFRESH"}). */
    public String extractTokenType(Claims claims) {
        return claims.get(SecurityConstants.CLAIM_TOKEN_TYPE, String.class);
    }

    /** Extracts the unique token ID ({@code jti} claim) used for blacklisting. */
    public String extractJti(Claims claims) {
        return claims.getId();
    }

    /** Returns {@code true} if {@code tokenType == "ACCESS"}. */
    public boolean isAccessToken(Claims claims) {
        return SecurityConstants.TOKEN_TYPE_ACCESS.equals(extractTokenType(claims));
    }

    /** Returns {@code true} if {@code tokenType == "REFRESH"}. */
    public boolean isRefreshToken(Claims claims) {
        return SecurityConstants.TOKEN_TYPE_REFRESH.equals(extractTokenType(claims));
    }

    // ── Private builder ───────────────────────────────────────────────────

    private String buildToken(UUID userId,
                              String email,
                              String phone,
                              List<String> roles,
                              String tokenType,
                              long expiryMs) {
        if (privateKey == null) {
            throw new IllegalStateException(
                    "This JwtUtil instance is verify-only (no private key configured). " +
                    "Token generation is only available in auth-service.");
        }

        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expiryMs);

        var builder = Jwts.builder()
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())       // jti — unique per token
                .issuedAt(now)
                .expiration(expiry)
                .claim(SecurityConstants.CLAIM_USER_ID,    userId.toString())
                .claim(SecurityConstants.CLAIM_TOKEN_TYPE, tokenType)
                .signWith(privateKey);                  // JJWT auto-selects RS256 for RSA keys

        if (email != null) {
            builder.claim(SecurityConstants.CLAIM_EMAIL, email);
        }
        if (phone != null && !phone.isBlank()) {
            builder.claim(SecurityConstants.CLAIM_PHONE, phone);
        }
        if (!roles.isEmpty()) {
            builder.claim(SecurityConstants.CLAIM_ROLES, String.join(",", roles));
        }

        return builder.compact();
    }
}