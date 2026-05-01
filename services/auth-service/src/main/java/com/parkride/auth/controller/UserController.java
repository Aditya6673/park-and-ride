package com.parkride.auth.controller;

import com.parkride.auth.dto.UpdateProfileRequest;
import com.parkride.auth.dto.UserResponse;
import com.parkride.auth.service.AuthService;
import com.parkride.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

@Tag(name = "User Profile", description = "View and update the authenticated user's profile")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final AuthService authService;

    @Operation(summary = "Get the current user's profile")
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserResponse>> getProfile(
            @AuthenticationPrincipal String userId) {
        UserResponse response = authService.getProfile(Objects.requireNonNull(UUID.fromString(userId)));
        return ResponseEntity.ok(ApiResponse.success("Profile retrieved", response));
    }

    @Operation(summary = "Update the current user's profile")
    @PatchMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody UpdateProfileRequest request) {
        UserResponse response = authService.updateProfile(Objects.requireNonNull(UUID.fromString(userId)), request);
        return ResponseEntity.ok(ApiResponse.success("Profile updated", response));
    }
}
