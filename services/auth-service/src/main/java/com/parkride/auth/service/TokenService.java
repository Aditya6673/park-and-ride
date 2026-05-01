package com.parkride.auth.service;

import com.parkride.auth.domain.RefreshToken;
import com.parkride.auth.domain.User;
import com.parkride.auth.exception.InvalidTokenException;
import com.parkride.auth.repository.RefreshTokenRepository;
import com.parkride.security.JwtUtil;
import com.parkride.security.SecurityConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.lang.NonNull;
import java.util.UUID;

/**
 * Manages JWT access token issuance and refresh token lifecycle.
 *
 * <p>Access tokens are stateless JWTs — revoked via Redis blacklist.
 * Refresh tokens are stored in PostgreSQL as SHA-256 hashes with full rotation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final String BLACKLIST_PREFIX = "blacklist:";

    private final JwtUtil jwtUtil;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RedisTemplate<String, String> redisTemplate;

    // ── Access token ──────────────────────────────────────────────────────

    public String generateAccessToken(User user) {
        return jwtUtil.generateAccessToken(
                user.getId(),
                user.getEmail(),
                user.getRoles().stream()
                        .map(r -> r.getName().name())
                        .toList()
        );
    }

    /**
     * Blacklists a JWT access token in Redis until its natural expiry.
     * Called on logout — prevents the token from being used even before it expires.
     */
    public void blacklistAccessToken(String rawToken) {
        try {
            var claims = jwtUtil.validateAndExtractClaims(rawToken);
            String jti = jwtUtil.extractJti(claims);
            long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remainingMs > 0) {
                redisTemplate.opsForValue().set(
                        BLACKLIST_PREFIX + jti,
                        "1",
                        Objects.requireNonNull(Duration.ofMillis(remainingMs))
                );
                log.debug("Blacklisted token JTI {} for {}ms", jti, remainingMs);
            }
        } catch (Exception e) {
            log.warn("Could not blacklist token (already expired or invalid): {}", e.getMessage());
        }
    }

    // ── Refresh token ─────────────────────────────────────────────────────

    @Transactional
    public String generateAndPersistRefreshToken(User user, String deviceInfo) {
        String rawToken = jwtUtil.generateRefreshToken(user.getId());
        String tokenHash = sha256(rawToken);

        RefreshToken entity = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plusMillis(SecurityConstants.REFRESH_TOKEN_EXPIRY_MS))
                .deviceInfo(deviceInfo)
                .build();

        refreshTokenRepository.save(Objects.requireNonNull(entity));
        return rawToken;
    }

    /**
     * Validates the presented refresh token, revokes it, and issues a brand-new one.
     *
     * @param rawRefreshToken the raw JWT string from the client cookie or body
     * @return the new refresh token string (to be set as cookie again)
     * @throws InvalidTokenException if the token is unknown, revoked, or expired
     */
    @Transactional
    public RotatedTokens rotateRefreshToken(String rawRefreshToken) {
        // 1. Validate JWT signature and expiry
        var claims = jwtUtil.validateAndExtractClaims(rawRefreshToken);
        if (!jwtUtil.isRefreshToken(claims)) {
            throw new InvalidTokenException("Provided token is not a refresh token");
        }

        // 2. Look up by hash
        String hash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not recognised"));

        // 3. Validate stored record
        if (!stored.isValid()) {
            // Possible token reuse attack — revoke all tokens for this user
            refreshTokenRepository.revokeAllByUserId(stored.getUserId());
            throw new InvalidTokenException("Refresh token has already been used or expired. All sessions revoked.");
        }

        // 4. Revoke the consumed token
        stored.revoke();
        refreshTokenRepository.save(stored);

        return new RotatedTokens(Objects.requireNonNull(stored.getUserId()), hash);
    }

    @Transactional
    public void revokeAllUserTokens(UUID userId) {
        int count = refreshTokenRepository.revokeAllByUserId(userId);
        log.info("Revoked {} refresh tokens for user {}", count, userId);
    }

    /** Scheduled cleanup — runs every day at 02:00 AM. */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredAndRevoked(Instant.now());
        log.info("Purged {} expired/revoked refresh tokens", deleted);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Value object returned by {@link #rotateRefreshToken}. */
    public record RotatedTokens(@NonNull UUID userId, String oldTokenHash) {}
}
