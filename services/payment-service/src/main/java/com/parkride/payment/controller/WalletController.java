package com.parkride.payment.controller;

import com.parkride.dto.ApiResponse;
import com.parkride.payment.dto.TopUpRequest;
import com.parkride.payment.dto.TransactionResponse;
import com.parkride.payment.dto.WalletResponse;
import com.parkride.payment.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API for wallet management.
 *
 * <p>All endpoints require a valid JWT — the authenticated user ID is extracted
 * from the security context (set by {@code JwtAuthFilter}).
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Wallet balance and transaction management")
public class WalletController {

    private final WalletService walletService;

    // ── GET /api/v1/payments/wallet ───────────────────────────────────────────

    @GetMapping("/wallet")
    @Operation(summary = "Get own wallet balance and loyalty points")
    public ResponseEntity<ApiResponse<WalletResponse>> getWallet(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        WalletResponse wallet = walletService.getWallet(userId);
        return ResponseEntity.ok(ApiResponse.success("Wallet retrieved", wallet));
    }

    // ── POST /api/v1/payments/wallet/topup ───────────────────────────────────

    @PostMapping("/wallet/topup")
    @Operation(summary = "Add funds to own wallet")
    public ResponseEntity<ApiResponse<WalletResponse>> topUp(
            @Valid @RequestBody TopUpRequest request,
            Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        WalletResponse wallet = walletService.topUp(userId, request.getAmount());
        return ResponseEntity.ok(ApiResponse.success("Wallet topped up successfully", wallet));
    }

    // ── GET /api/v1/payments/transactions ────────────────────────────────────

    @GetMapping("/transactions")
    @Operation(summary = "Get paginated transaction history for own wallet")
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> getTransactions(
            Authentication auth,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        UUID userId = UUID.fromString(auth.getName());
        Page<TransactionResponse> transactions = walletService.getTransactions(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success("Transactions retrieved", transactions));
    }
}
