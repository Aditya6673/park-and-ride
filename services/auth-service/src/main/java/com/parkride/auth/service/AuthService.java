package com.parkride.auth.service;

import com.parkride.auth.domain.Role;
import com.parkride.auth.domain.User;
import com.parkride.auth.dto.*;
import com.parkride.auth.exception.AuthException;
import com.parkride.auth.exception.InvalidTokenException;
import com.parkride.auth.exception.UserAlreadyExistsException;
import com.parkride.auth.repository.RoleRepository;
import com.parkride.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Core authentication business logic.
 *
 * <p>All public methods are {@code @Transactional} — a failure at any step
 * rolls back the entire operation, preventing partial state (e.g., user created
 * but roles not assigned).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    // ── Registration ──────────────────────────────────────────────────────

    @Transactional
    public AuthResponse register(RegisterRequest request, String deviceInfo) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException(request.getEmail());
        }

        Role userRole = roleRepository.findByName(Role.RoleName.ROLE_USER)
                .orElseThrow(() -> new IllegalStateException(
                        "ROLE_USER not seeded in database — check Flyway migration V2"));

        User user = User.builder()
                .email(request.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .verified(false)
                .enabled(true)
                .build();

        user.addRole(userRole);

        // Generate email verification token
        String verificationToken = generateSecureToken();
        user.setVerificationTokenHash(sha256(verificationToken));
        user.setVerificationTokenExpiresAt(Instant.now().plusSeconds(24 * 60 * 60)); // 24h

        userRepository.save(user);
        log.info("Registered new user: {}", user.getEmail());

        // TODO: publish UserRegisteredEvent to Kafka (Phase 1 Week 5 — Notification Service)
        // TODO: send verification email via Notification Service

        String accessToken  = tokenService.generateAccessToken(user);
        String refreshToken = tokenService.generateAndPersistRefreshToken(user, deviceInfo);

        return buildAuthResponse(user, accessToken, refreshToken);
    }

    // ── Login ─────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail().toLowerCase().trim())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!user.isAccountNonLocked()) {
            throw new LockedException("Account temporarily locked. Try again later.");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
            );
        } catch (BadCredentialsException e) {
            user.incrementFailedAttempts();
            userRepository.save(user);
            log.warn("Failed login attempt for {} (attempt {})", user.getEmail(), user.getFailedLoginAttempts());
            throw e;
        }

        user.resetFailedAttempts();
        userRepository.save(user);

        String accessToken  = tokenService.generateAccessToken(user);
        String refreshToken = tokenService.generateAndPersistRefreshToken(user, request.getDeviceInfo());

        log.info("User logged in: {}", user.getEmail());
        return buildAuthResponse(user, accessToken, refreshToken);
    }

    // ── Logout ────────────────────────────────────────────────────────────

    @Transactional
    public void logout(String accessToken, String rawRefreshToken) {
        tokenService.blacklistAccessToken(accessToken);
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            try {
                var rotated = tokenService.rotateRefreshToken(rawRefreshToken);
                // Immediately revoke the "rotated" entry — we don't issue a new one on logout
                tokenService.revokeAllUserTokens(rotated.userId());
            } catch (InvalidTokenException e) {
                log.debug("Logout: refresh token invalid or already expired — ignoring");
            }
        }
    }

    // ── Token Refresh ─────────────────────────────────────────────────────

    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        var rotated = tokenService.rotateRefreshToken(rawRefreshToken);

        User user = userRepository.findById(rotated.userId())
                .orElseThrow(() -> new InvalidTokenException("User no longer exists"));

        if (!user.isEnabled() || !user.isAccountNonLocked()) {
            throw new AuthException("Account is disabled or locked");
        }

        String newAccessToken  = tokenService.generateAccessToken(user);
        String newRefreshToken = tokenService.generateAndPersistRefreshToken(user, rotated.deviceInfo());

        return buildAuthResponse(user, newAccessToken, newRefreshToken);
    }

    // ── Email Verification ────────────────────────────────────────────────

    @Transactional
    public void verifyEmail(String token) {
        String hash = sha256(token);
        User user = userRepository.findByVerificationTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired verification token"));

        if (user.getVerificationTokenExpiresAt() != null &&
            Instant.now().isAfter(user.getVerificationTokenExpiresAt())) {
            throw new InvalidTokenException("Verification token has expired. Request a new one.");
        }

        user.setVerified(true);
        user.setVerificationTokenHash(null);
        user.setVerificationTokenExpiresAt(null);
        userRepository.save(user);
        log.info("Email verified for user: {}", user.getEmail());
    }

    // ── Password Reset ────────────────────────────────────────────────────

    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmail(email.toLowerCase().trim()).ifPresent(user -> {
            String token = generateSecureToken();
            user.setPasswordResetTokenHash(sha256(token));
            user.setPasswordResetTokenExpiresAt(Instant.now().plusSeconds(60 * 60)); // 1h
            userRepository.save(user);
            // TODO: publish password reset event to Kafka → Notification Service sends email
            log.info("Password reset requested for: {}", email);
        });
        // Always return success to prevent email enumeration
    }

    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String hash = sha256(request.getToken());
        User user = userRepository.findByPasswordResetTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired password reset token"));

        if (user.getPasswordResetTokenExpiresAt() != null &&
            Instant.now().isAfter(user.getPasswordResetTokenExpiresAt())) {
            throw new InvalidTokenException("Password reset token has expired. Request a new one.");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetTokenExpiresAt(null);
        user.resetFailedAttempts();
        userRepository.save(user);

        // Revoke all refresh tokens — forces re-login everywhere
        tokenService.revokeAllUserTokens(user.getId());
        log.info("Password reset completed for user: {}", user.getEmail());
    }

    // ── Profile ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public UserResponse getProfile(@NonNull UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("User not found"));
        return toUserResponse(user);
    }

    @Transactional
    public UserResponse updateProfile(@NonNull UUID userId, UpdateProfileRequest request) {
        User user = Objects.requireNonNull(
                userRepository.findById(userId)
                        .orElseThrow(() -> new AuthException("User not found")));

        if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
        if (request.getLastName()  != null) user.setLastName(request.getLastName());
        if (request.getPhone()     != null) user.setPhone(request.getPhone());

        return toUserResponse(userRepository.save(user));
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private AuthResponse buildAuthResponse(User user, String accessToken, String refreshToken) {
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getName().name())
                .toList();

        return AuthResponse.builder()
                .accessToken(accessToken)
                .expiresIn(com.parkride.security.SecurityConstants.ACCESS_TOKEN_EXPIRY_MS / 1000)
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .roles(roles)
                .verified(user.isVerified())
                .refreshToken(refreshToken)   // carried to controller; @JsonIgnore keeps it out of JSON
                .build();
    }

    private UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .verified(user.isVerified())
                .roles(user.getRoles().stream().map(r -> r.getName().name()).toList())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
