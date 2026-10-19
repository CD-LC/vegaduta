package com.agenticai.core.payments.commerce;

/**
 * Lifecycle: an INTENT is born ACTIVE (it IS the authorization); a CART is
 * born PENDING_AUTHORIZATION, becomes AUTHORIZED when the buyer signs, and
 * CAPTURED when the wallet debit settles. FAILED marks a capture attempt
 * that could not settle; REFUNDED a captured mandate reversed.
 */
public enum CommerceMandateStatus {
    ACTIVE,
    PENDING_AUTHORIZATION,
    AUTHORIZED,
    CAPTURED,
    REFUNDED,
    FAILED,
    REVOKED,
    EXPIRED
}
