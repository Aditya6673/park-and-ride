package com.parkride.payment.domain;

/**
 * Terminal state of a wallet transaction.
 */
public enum TransactionStatus {
    /** Processing has started but not yet completed. */
    PENDING,
    /** Funds moved successfully. */
    SUCCESS,
    /** Processing failed (insufficient balance, DB error). */
    FAILED,
    /** A prior SUCCESS debit has been reversed. */
    REFUNDED
}
