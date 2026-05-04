package com.parkride.payment.service;

import com.parkride.payment.domain.Transaction;
import com.parkride.payment.domain.TransactionStatus;
import com.parkride.payment.domain.TransactionType;
import com.parkride.payment.domain.Wallet;
import com.parkride.payment.exception.WalletNotFoundException;
import com.parkride.payment.repository.TransactionRepository;
import com.parkride.payment.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WalletService — unit tests")
@SuppressWarnings("null")
class WalletServiceTest {

    @Mock WalletRepository     walletRepository;
    @Mock TransactionRepository transactionRepository;

    @InjectMocks WalletService walletService;

    private UUID userId;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        wallet = Wallet.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .balance(new BigDecimal("500.00"))
                .loyaltyPoints(0)
                .version(0L)
                .build();
    }

    // ── getWallet ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getWallet — returns wallet response when user exists")
    void getWallet_found() {
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));

        var response = walletService.getWallet(userId);

        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getBalance()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("getWallet — throws WalletNotFoundException when no wallet exists")
    void getWallet_notFound_throws() {
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getWallet(userId))
                .isInstanceOf(WalletNotFoundException.class)
                .hasMessageContaining(userId.toString());
    }

    // ── getOrCreateWallet ─────────────────────────────────────────────────────

    @Test
    @DisplayName("getOrCreateWallet — returns existing wallet without creating a new one")
    void getOrCreateWallet_existingUser_returnsExisting() {
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));

        Wallet result = walletService.getOrCreateWallet(userId);

        assertThat(result.getId()).isEqualTo(wallet.getId());
        verify(walletRepository, never()).save(any());
    }

    @Test
    @DisplayName("getOrCreateWallet — creates new wallet with zero balance for new user")
    void getOrCreateWallet_newUser_createsWallet() {
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Wallet result = walletService.getOrCreateWallet(userId);

        assertThat(result.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getUserId()).isEqualTo(userId);
        verify(walletRepository).save(any());
    }

    // ── topUp ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("topUp — adds amount to balance and records CREDIT transaction")
    void topUp_addsToBalance() {
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any())).thenReturn(wallet);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = walletService.topUp(userId, new BigDecimal("100.00"));

        assertThat(response.getBalance()).isEqualByComparingTo("600.00");
        verify(transactionRepository).save(argThat(tx ->
                tx.getType() == TransactionType.CREDIT &&
                tx.getAmount().compareTo(new BigDecimal("100.00")) == 0 &&
                tx.getStatus() == TransactionStatus.SUCCESS
        ));
    }

    // ── debit ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("debit — deducts amount and records SUCCESS transaction")
    void debit_sufficientBalance_success() {
        String key = UUID.randomUUID() + ":BOOKING_CONFIRMED";
        UUID bookingId = UUID.randomUUID();

        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(walletRepository.save(any())).thenReturn(wallet);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction tx = walletService.debit(userId, new BigDecimal("200.00"), key, bookingId);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(tx.getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(wallet.getBalance()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("debit — records FAILED transaction when balance is insufficient")
    void debit_insufficientBalance_recordsFailed() {
        String key = UUID.randomUUID() + ":BOOKING_CONFIRMED";
        UUID bookingId = UUID.randomUUID();

        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction tx = walletService.debit(userId, new BigDecimal("999.00"), key, bookingId);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getFailureReason()).contains("Insufficient");
        // Balance must NOT have changed
        assertThat(wallet.getBalance()).isEqualByComparingTo("500.00");
        verify(walletRepository, never()).save(any());
    }

    @Test
    @DisplayName("debit — idempotent: returns existing transaction on duplicate key")
    void debit_duplicateKey_returnsExistingTransaction() {
        String key = UUID.randomUUID() + ":BOOKING_CONFIRMED";
        UUID bookingId = UUID.randomUUID();
        Transaction existing = Transaction.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(key)
                .status(TransactionStatus.SUCCESS)
                .build();

        when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        Transaction tx = walletService.debit(userId, new BigDecimal("200.00"), key, bookingId);

        assertThat(tx.getId()).isEqualTo(existing.getId());
        // Must NOT touch the wallet or create a new transaction
        verify(walletRepository, never()).findByUserId(any());
        verify(transactionRepository, never()).save(any());
    }

    // ── credit ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("credit — adds amount to balance and records CREDIT transaction")
    void credit_addsToBalance() {
        String key = UUID.randomUUID() + ":BOOKING_CANCELLED";
        UUID bookingId = UUID.randomUUID();

        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(walletRepository.save(any())).thenReturn(wallet);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction tx = walletService.credit(userId, new BigDecimal("150.00"), key, bookingId);

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(tx.getType()).isEqualTo(TransactionType.CREDIT);
        assertThat(wallet.getBalance()).isEqualByComparingTo("650.00");
    }

    @Test
    @DisplayName("credit — idempotent: returns existing transaction on duplicate key")
    void credit_duplicateKey_returnsExistingTransaction() {
        String key = UUID.randomUUID() + ":BOOKING_CANCELLED";
        UUID bookingId = UUID.randomUUID();
        Transaction existing = Transaction.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(key)
                .status(TransactionStatus.SUCCESS)
                .build();

        when(transactionRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        Transaction tx = walletService.credit(userId, new BigDecimal("150.00"), key, bookingId);

        assertThat(tx.getId()).isEqualTo(existing.getId());
        verify(walletRepository, never()).findByUserId(any());
        verify(transactionRepository, never()).save(any());
    }
}
