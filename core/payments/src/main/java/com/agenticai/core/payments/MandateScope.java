package com.agenticai.core.payments;

/**
 * What a {@link PaymentMandate} authorizes the agent to do: COLLECT (request
 * money IN - payment links / UPI collect), PAY (money OUT - payouts, future),
 * or BOTH. Enforced by {@link MandateEnforcer} per transaction type.
 */
public enum MandateScope {
    COLLECT,
    PAY,
    BOTH;

    public boolean allowsCollection() {
        return this == COLLECT || this == BOTH;
    }

    public boolean allowsPayout() {
        return this == PAY || this == BOTH;
    }
}
