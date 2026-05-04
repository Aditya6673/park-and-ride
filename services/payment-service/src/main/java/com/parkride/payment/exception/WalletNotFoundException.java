package com.parkride.payment.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException(UUID userId) {
        super("Wallet not found for user: " + userId);
    }
}
