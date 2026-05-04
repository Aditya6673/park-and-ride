package com.parkride.payment.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;

@ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(BigDecimal required, BigDecimal available) {
        super(String.format(
            "Insufficient wallet balance. Required: ₹%.2f, Available: ₹%.2f",
            required, available));
    }
}
