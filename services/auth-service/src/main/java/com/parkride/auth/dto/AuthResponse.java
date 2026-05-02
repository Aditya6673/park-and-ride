package com.parkride.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * Returned on successful login and token refresh.
 *
 * <p>The refresh token is intentionally NOT included in the JSON body — it is set
 * as an {@code HttpOnly; Secure; SameSite=Strict} cookie by the controller so that
 * JavaScript cannot access it (XSS mitigation).
 *
 * <p>{@code refreshToken} is carried internally from the service to the controller
 * via {@link JsonIgnore} — it never appears in serialized responses.
 */
@Getter
@Builder
public class AuthResponse {

    @JsonProperty("accessToken")
    private String accessToken;

    @JsonProperty("tokenType")
    @Builder.Default
    private String tokenType = "Bearer";

    @JsonProperty("expiresIn")
    private long expiresIn;   // seconds until access token expires

    @JsonProperty("userId")
    private UUID userId;

    @JsonProperty("email")
    private String email;

    @JsonProperty("firstName")
    private String firstName;

    @JsonProperty("lastName")
    private String lastName;

    @JsonProperty("roles")
    private List<String> roles;

    @JsonProperty("verified")
    private boolean verified;

    /** Internal carrier only — never serialized. Set as HttpOnly cookie by the controller. */
    @JsonIgnore
    private String refreshToken;
}
