package com.agenticai.core.payments;

/** Lifecycle of a {@link PaymentMandate}. REVOKED/SUSPENDED are terminal-at-execution states - an in-flight call re-checks status before executing. */
public enum MandateStatus {
    ACTIVE,
    REVOKED,
    EXPIRED,
    SUSPENDED;
}
