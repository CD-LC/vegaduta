package com.agenticai.core.payments;

/** Event-type constants for the payment outbox stream (§E1's {@code payment.status.changed} plus lifecycle events). */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    /** {@code payment.status.changed} - every PaymentTransaction status transition publishes one of these. */
    public static final String STATUS_CHANGED = "payment.status.changed";

    public static final String MANDATE_GRANTED = "payment.mandate.granted";
    public static final String MANDATE_REVOKED = "payment.mandate.revoked";
}
