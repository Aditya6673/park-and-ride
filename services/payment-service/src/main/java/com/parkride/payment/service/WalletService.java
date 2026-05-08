package com.parkride.payment.service;

import com.parkride.payment.domain.Transaction;
import com.parkride.payment.domain.TransactionStatus;
import com.parkride.payment.domain.TransactionType;
import com.parkride.payment.domain.Wallet;
import com.parkride.payment.dto.WalletResponse;
import com.parkride.payment.dto.TransactionResponse;
import com.parkride.payment.exception.WalletNotFoundException;
import com.parkride.payment.repository.TransactionRepository;
import com.parkride.payment.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Core wallet business logic.
 *
 * <p><b>Concurrency:</b> All balance-mutating methods are {@code @Transactional}.
 * The {@code @Version} field on {@link Wallet} ensures optimistic locking —
 * concurrent updates throw {@link org.springframework.dao.OptimisticLockingFailureException},
 * which the caller (Kafka consumer) can retry safely.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // Spring Data save() and JPA return types are @NonNull at runtime
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    // ─── Read operations ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public WalletResponse getWallet(UUID userId) {
        Wallet wallet = findByUserId(userId);
        return toWalletResponse(wallet);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactions(UUID userId, Pageable pageable) {
        Wallet wallet = findByUserId(userId);
        return transactionRepository.findByWalletOrderByCreatedAtDesc(wallet, pageable)
                .map(this::toTransactionResponse);
    }

    // ─── Write operations ─────────────────────────────────────────────────────

    /**
     * Creates a wallet for a new user with zero balance.
     * Called when a BOOKING_CONFIRMED event arrives for a user with no wallet yet.
     */
    @Transactional
    public Wallet getOrCreateWallet(UUID userId) {
        return walletRepository.findByUserId(userId).orElseGet(() -> {
            log.info("Creating new wallet for user {}", userId);
            return walletRepository.save(Wallet.builder()
                    .userId(userId)
                    .balance(BigDecimal.ZERO)
                    .loyaltyPoints(0)
                    .build());
        });
    }

    /**
     * Adds funds to a wallet (user-initiated top-up).
     */
    @Transactional
    public WalletResponse topUp(UUID userId, BigDecimal amount) {
        Wallet wallet = getOrCreateWallet(userId);
        wallet.setBalance(wallet.getBalance().add(amount));

        Transaction tx = Transaction.builder()
                .wallet(wallet)
                .type(TransactionType.CREDIT)
                .amount(amount)
                .idempotencyKey("topup:" + UUID.randomUUID())
                .referenceType("TOPUP")
                .status(TransactionStatus.SUCCESS)
                .build();

        walletRepository.save(wallet);
        transactionRepository.save(tx);

        log.info("Top-up ₹{} for user {} — new balance: ₹{}", amount, userId, wallet.getBalance());
        return toWalletResponse(wallet);
    }

    /**
     * Debits a wallet for a booking charge.
     *
     * @param userId          the user being charged
     * @param amount          amount to debit
     * @param idempotencyKey  unique key (bookingId:BOOKING_CONFIRMED) — prevents double-charge on Kafka retry
     * @param referenceId     the booking ID
     */
    @Transactional
    public Transaction debit(UUID userId, BigDecimal amount, String idempotencyKey, UUID referenceId) {
        // Idempotency guard — if already processed, return the existing transaction
        return transactionRepository.findByIdempotencyKey(idempotencyKey).orElseGet(() -> {
            Wallet wallet = getOrCreateWallet(userId);

            if (wallet.getBalance().compareTo(amount) < 0) {
                log.warn("Insufficient balance for user {}: required ₹{}, available ₹{}",
                        userId, amount, wallet.getBalance());
                // Record the failed transaction so it's visible in history
                Transaction failed = Transaction.builder()
                        .wallet(wallet)
                        .type(TransactionType.DEBIT)
                        .amount(amount)
                        .idempotencyKey(idempotencyKey)
                        .referenceId(referenceId)
                        .referenceType("BOOKING")
                        .status(TransactionStatus.FAILED)
                        .failureReason("Insufficient balance")
                        .build();
                return transactionRepository.save(failed);
            }

            wallet.setBalance(wallet.getBalance().subtract(amount));
            walletRepository.save(wallet);

            Transaction tx = Transaction.builder()
                    .wallet(wallet)
                    .type(TransactionType.DEBIT)
                    .amount(amount)
                    .idempotencyKey(idempotencyKey)
                    .referenceId(referenceId)
                    .referenceType("BOOKING")
                    .status(TransactionStatus.SUCCESS)
                    .build();

            Transaction saved = transactionRepository.save(tx);
            log.info("Debited ₹{} from user {} for booking {} — new balance: ₹{}",
                    amount, userId, referenceId, wallet.getBalance());
            return saved;
        });
    }

    /**
     * Credits a wallet (refund on booking cancellation).
     *
     * @param userId          the user receiving the refund
     * @param amount          amount to refund
     * @param idempotencyKey  unique key (bookingId:BOOKING_CANCELLED) — prevents double-refund
     * @param referenceId     the booking ID
     */
    @Transactional
    public Transaction credit(UUID userId, BigDecimal amount, String idempotencyKey, UUID referenceId) {
        return transactionRepository.findByIdempotencyKey(idempotencyKey).orElseGet(() -> {
            Wallet wallet = getOrCreateWallet(userId);
            wallet.setBalance(wallet.getBalance().add(amount));
            walletRepository.save(wallet);

            Transaction tx = Transaction.builder()
                    .wallet(wallet)
                    .type(TransactionType.CREDIT)
                    .amount(amount)
                    .idempotencyKey(idempotencyKey)
                    .referenceId(referenceId)
                    .referenceType("BOOKING")
                    .status(TransactionStatus.SUCCESS)
                    .build();

            Transaction saved = transactionRepository.save(tx);
            log.info("Refunded ₹{} to user {} for booking {} — new balance: ₹{}",
                    amount, userId, referenceId, wallet.getBalance());
            return saved;
        });
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Wallet findByUserId(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new WalletNotFoundException(userId));
    }

    private WalletResponse toWalletResponse(Wallet w) {
        return WalletResponse.builder()
                .id(w.getId())
                .userId(w.getUserId())
                .balance(w.getBalance())
                .loyaltyPoints(w.getLoyaltyPoints())
                .updatedAt(w.getUpdatedAt())
                .build();
    }

    private TransactionResponse toTransactionResponse(Transaction t) {
        return TransactionResponse.builder()
                .id(t.getId())
                .type(t.getType())
                .status(t.getStatus())
                .amount(t.getAmount())
                .referenceId(t.getReferenceId())
                .referenceType(t.getReferenceType())
                .failureReason(t.getFailureReason())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
