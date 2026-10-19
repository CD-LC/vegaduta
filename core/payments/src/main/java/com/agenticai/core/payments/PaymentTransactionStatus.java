package com.agenticai.core.payments;

/**
 * {@link PaymentTransaction} state machine (§E1):
 * INITIATED -&gt; PENDING_APPROVAL -&gt; AWAITING_PAYMENT -&gt; CAPTURED.
 * Terminal-ish: CAPTURED, FAILED, EXPIRED, REFUNDED. A FAILED/EXPIRED row
 * always has a matching REVERSAL in {@code mandate_ledger} releasing its
 * cap reservation.
 */
public enum PaymentTransactionStatus {
    INITIATED,
    PENDING_APPROVAL,
    AWAITING_PAYMENT,
    CAPTURED,
    FAILED,
    EXPIRED,
    REFUNDED;

    public boolean isTerminal() {
        return this == CAPTURED || this == FAILED || this == EXPIRED || this == REFUNDED;
    }
}
