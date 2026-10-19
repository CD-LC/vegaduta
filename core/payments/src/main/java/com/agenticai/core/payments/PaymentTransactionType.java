package com.agenticai.core.payments;

/** The three instrumented money movements a mandate can cover (§E1). PAYOUT exists for the schema/AP2 shape only - no adapter implements it yet (RazorpayX is a separate product integration). */
public enum PaymentTransactionType {
    PAYMENT_LINK,
    UPI_COLLECT,
    PAYOUT;
}
