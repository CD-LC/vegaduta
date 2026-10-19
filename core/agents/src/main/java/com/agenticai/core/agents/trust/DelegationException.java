package com.agenticai.core.agents.trust;

/**
 * A delegation check that failed - minting, introspection or verification.
 * Codes are stable strings (part of the wire contract for introspection
 * results and audit details), not an enum, so they travel cleanly in JSON.
 */
public class DelegationException extends RuntimeException {

    private final String code;

    public DelegationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
