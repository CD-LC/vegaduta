package com.agenticai.core.payments.commerce;

/** The two halves of the AP2-style mandate chain, in one table. */
public enum CommerceMandateKind {
    /** "User authorizes this agent to shop for X up to N" - open-ended, ceiling-only. */
    INTENT,
    /** "User authorized THIS specific spend" - concrete items, total, payee. */
    CART
}
