package com.agenticai.core.payments;

/**
 * Per-mandate approval policy (Build Specs §E1): ALWAYS routes every
 * transaction through {@code HumanApprovalGateService}, ABOVE_THRESHOLD only
 * when the amount exceeds {@code approval_threshold_paise}, NEVER executes
 * under the mandate's own signed authority. Decision is amount-aware, which
 * is why this lives on the mandate rather than in the action-type-level
 * {@code ApprovalPolicyService} - see {@code PaymentToolService}'s javadoc.
 */
public enum MandateApprovalPolicy {
    ALWAYS,
    ABOVE_THRESHOLD,
    NEVER;

    public boolean requiresApproval(long amountPaise, Long thresholdPaise) {
        return switch (this) {
            case ALWAYS -> true;
            case ABOVE_THRESHOLD -> thresholdPaise != null && amountPaise > thresholdPaise;
            case NEVER -> false;
        };
    }
}
