package com.agenticai.core.guardrails;

/**
 * Outcome of {@link ToolCallVerifierService#verify}. {@code reason} is
 * always populated (even when {@code approved} is true - "matches the
 * user's request" is a valid, loggable reason) since both the tool-call log
 * and a rejected-call explanation back to the model want a human-readable
 * sentence, not just a boolean.
 */
public record VerificationResult(boolean approved, String reason) {

    public static VerificationResult approved(String reason) {
        return new VerificationResult(true, reason);
    }

    public static VerificationResult rejected(String reason) {
        return new VerificationResult(false, reason);
    }
}
