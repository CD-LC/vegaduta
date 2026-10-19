package com.agenticai.core.agents.tools.x402;

/**
 * An inbound x402 verification/settlement failure. Codes are stable strings (part of the
 * payment-rejection wire contract), not an enum.
 */
public class X402Exception extends RuntimeException {

    private final String code;

    public X402Exception(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
