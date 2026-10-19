package com.agenticai.core.payments;

/**
 * A mandate check that failed inside {@link MandateEnforcer} - the tool
 * surface turns {@link #getCode()} into a structured model-readable error.
 * Codes are stable strings (part of the tool-result contract and audit
 * details), not an enum, so they travel cleanly in JSON.
 */
public class MandateViolationException extends RuntimeException {

    private final String code;

    public MandateViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
