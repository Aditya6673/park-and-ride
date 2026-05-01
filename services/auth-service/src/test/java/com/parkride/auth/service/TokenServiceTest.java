package com.parkride.auth.service;

import com.parkride.auth.domain.RefreshToken;
import com.parkride.auth.domain.Role;
import com.parkride.auth.domain.User;
import com.parkride.auth.exception.InvalidTokenException;
import com.parkride.auth.repository.RefreshTokenRepository;
import com.parkride.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenService Unit Tests")
class TokenServiceTest {

    private static final String TEST_SECRET = "test-secret-for-unit-tests-only-32ch!!";

    @Mock private RefreshTokenRepository          refreshTokenRepository;
    @Mock private RedisTemplate<String, String>   redisTemplate;


    @InjectMocks
    private TokenService tokenService;

    private final JwtUtil jwtUtil = new JwtUtil(TEST_SECRET);

    @BeforeEach
    void injectJwtUtil() throws Exception {
        var field = TokenService.class.getDeclaredField("jwtUtil");
        field.setAccessible(true);
        field.set(tokenService, jwtUtil);
    }

    private User testUser() {
        Role role = Role.builder().id(UUID.randomUUID()).name(Role.RoleName.ROLE_USER).build();
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .firstName("Test")
                .lastName("User")
                .enabled(true)
                .build();
        user.addRole(role);
        return user;
    }

    // ── Access token ──────────────────────────────────────────────────────

    @Test
    @DisplayName("generateAccessToken: produces a valid JWT with correct claims")
    void generateAccessToken_producesValidJwt() {
        User user = testUser();
        String token = tokenService.generateAccessToken(user);

        assertThat(token).isNotBlank();
        var claims = jwtUtil.validateAndExtractClaims(token);
        assertThat(jwtUtil.extractUserId(claims)).isEqualTo(user.getId());
        assertThat(jwtUtil.extractEmail(claims)).isEqualTo(user.getEmail());
        assertThat(jwtUtil.isAccessToken(claims)).isTrue();
        assertThat(jwtUtil.extractRoles(claims)).contains("ROLE_USER");
    }

    // ── Refresh token rotation ────────────────────────────────────────────

    @Test
    @DisplayName("rotateRefreshToken: valid token returns rotated result and revokes old")
    void rotateRefreshToken_validToken_revokesOldAndReturnsUserId() {
        User user = testUser();
        String rawRefresh = jwtUtil.generateRefreshToken(user.getId());

        // Build the stored record that matches the hash of the raw token
        RefreshToken stored = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .tokenHash("placeholder") // will be matched by findByTokenHash mock
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(false)
                .build();

        given(refreshTokenRepository.findByTokenHash(anyString())).willReturn(Optional.of(stored));
        given(refreshTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var result = tokenService.rotateRefreshToken(rawRefresh);

        assertThat(result.userId()).isEqualTo(user.getId());
        assertThat(stored.isRevoked()).isTrue();
        then(refreshTokenRepository).should().save(stored);
    }

    @Test
    @DisplayName("rotateRefreshToken: unknown token throws InvalidTokenException")
    void rotateRefreshToken_unknownToken_throws() {
        User user = testUser();
        String rawRefresh = jwtUtil.generateRefreshToken(user.getId());

        given(refreshTokenRepository.findByTokenHash(anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> tokenService.rotateRefreshToken(rawRefresh))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("not recognised");
    }

    @Test
    @DisplayName("rotateRefreshToken: already-revoked token triggers full revocation")
    void rotateRefreshToken_revokedToken_revokesAllAndThrows() {
        User user = testUser();
        String rawRefresh = jwtUtil.generateRefreshToken(user.getId());

        RefreshToken stored = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .tokenHash("placeholder")
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(true)   // already revoked — reuse attack
                .build();

        given(refreshTokenRepository.findByTokenHash(anyString())).willReturn(Optional.of(stored));

        assertThatThrownBy(() -> tokenService.rotateRefreshToken(rawRefresh))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("All sessions revoked");

        then(refreshTokenRepository).should().revokeAllByUserId(user.getId());
    }
}
