package com.parkride.payment.domain;

/**
 * Direction of a wallet transaction.
 */
public enum TransactionType {
    /** Money leaving the wallet (booking charge). */
    DEBIT,
    /** Money entering the wallet (top-up or refund). */
    CREDIT
}
