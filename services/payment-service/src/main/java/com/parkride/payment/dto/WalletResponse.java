package com.parkride.payment.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class WalletResponse {

    private UUID id;
    private UUID userId;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal balance;

    private Integer loyaltyPoints;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant updatedAt;
}
