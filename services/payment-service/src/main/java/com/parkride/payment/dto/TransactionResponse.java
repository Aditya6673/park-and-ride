package com.parkride.payment.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.parkride.payment.domain.TransactionStatus;
import com.parkride.payment.domain.TransactionType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class TransactionResponse {

    private UUID id;
    private TransactionType type;
    private TransactionStatus status;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal amount;

    private UUID referenceId;
    private String referenceType;
    private String failureReason;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant createdAt;
}
