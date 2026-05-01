package com.parkride.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class RefreshRequest {

    /**
     * The refresh token — accepted either from request body OR read from the
     * {@code refresh_token} HttpOnly cookie. Controllers try the cookie first;
     * fall back to body for API clients that cannot use cookies.
     */
    @NotBlank(message = "Refresh token is required")
    private String refreshToken;
}
