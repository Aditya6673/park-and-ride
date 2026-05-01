package com.parkride.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Profile response for GET /api/v1/users/me and PATCH /api/v1/users/me */
@Getter
@Builder
public class UserResponse {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("email")
    private String email;

    @JsonProperty("firstName")
    private String firstName;

    @JsonProperty("lastName")
    private String lastName;

    @JsonProperty("phone")
    private String phone;

    @JsonProperty("verified")
    private boolean verified;

    @JsonProperty("roles")
    private List<String> roles;

    @JsonProperty("createdAt")
    private Instant createdAt;
}
