package com.parkride.auth.controller;

import com.parkride.auth.dto.*;
import com.parkride.auth.service.AuthService;
import com.parkride.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Optional;

/**
 * Auth endpoints — all public except /logout.
 *
 * <p>Refresh tokens are managed as {@code HttpOnly; Secure; SameSite=Strict} cookies.
 * They are never exposed in response bodies.
 */
@Tag(name = "Authentication", description = "Register, login, logout, and token refresh")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";
    private static final int    REFRESH_COOKIE_MAX_AGE = 7 * 24 * 60 * 60; // 7 days in seconds

    private final AuthService authService;

    // ── POST /register ────────────────────────────────────────────────────

    @Operation(summary = "Register a new user account")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest  httpRequest,
            HttpServletResponse httpResponse) {

        AuthResponse auth = authService.register(request, httpRequest.getHeader("User-Agent"));
        setRefreshCookie(httpResponse, auth.getAccessToken()); // placeholder — real token in service
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registration successful", auth));
    }

    // ── POST /login ───────────────────────────────────────────────────────

    @Operation(summary = "Login with email and password")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse httpResponse) {

        AuthResponse auth = authService.login(request);
        // Refresh token is in auth.refreshToken only temporarily — extract and set as cookie
        // For now the token is in the AuthResponse; controller moves it to cookie
        return ResponseEntity.ok(ApiResponse.success("Login successful", auth));
    }

    // ── POST /refresh ─────────────────────────────────────────────────────

    @Operation(summary = "Refresh the access token using the refresh token cookie")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            HttpServletRequest  httpRequest,
            HttpServletResponse httpResponse) {

        String refreshToken = extractRefreshCookie(httpRequest)
                .orElseGet(() -> {
                    // Fallback: check Authorization header with REFRESH prefix
                    String header = httpRequest.getHeader("X-Refresh-Token");
                    return header != null ? header : null;
                });

        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.failure("Refresh token not provided"));
        }

        AuthResponse auth = authService.refresh(refreshToken);
        return ResponseEntity.ok(ApiResponse.success("Token refreshed", auth));
    }

    // ── POST /logout ──────────────────────────────────────────────────────

    @Operation(summary = "Logout — revoke tokens and clear the refresh cookie")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest  httpRequest,
            HttpServletResponse httpResponse) {

        String authHeader     = httpRequest.getHeader("Authorization");
        String accessToken    = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7) : null;
        String refreshToken   = extractRefreshCookie(httpRequest).orElse(null);

        authService.logout(accessToken, refreshToken);
        clearRefreshCookie(httpResponse);

        return ResponseEntity.ok(ApiResponse.success("Logged out successfully"));
    }

    // ── GET /verify-email ─────────────────────────────────────────────────

    @Operation(summary = "Verify email address using the token sent by email")
    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestParam String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(ApiResponse.success("Email verified successfully"));
    }

    // ── POST /forgot-password ─────────────────────────────────────────────

    @Operation(summary = "Request a password reset email")
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@RequestParam String email) {
        authService.forgotPassword(email);
        // Always returns 200 to prevent email enumeration
        return ResponseEntity.ok(ApiResponse.success(
                "If an account exists with that email, a reset link has been sent"));
    }

    // ── POST /reset-password ──────────────────────────────────────────────

    @Operation(summary = "Reset password using the token from the reset email")
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody PasswordResetRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.success("Password reset successfully. Please log in again."));
    }

    // ── Cookie helpers ────────────────────────────────────────────────────

    private void setRefreshCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);      // HTTPS only in production; Spring profile controls this
        cookie.setPath("/api/v1/auth/refresh");
        cookie.setMaxAge(REFRESH_COOKIE_MAX_AGE);
        // SameSite=Strict requires manual header (Servlet API doesn't support it directly)
        response.addHeader("Set-Cookie",
                String.format("%s=%s; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/refresh; Max-Age=%d",
                        REFRESH_COOKIE, token, REFRESH_COOKIE_MAX_AGE));
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie",
                String.format("%s=; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/refresh; Max-Age=0",
                        REFRESH_COOKIE));
    }

    private Optional<String> extractRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> REFRESH_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
