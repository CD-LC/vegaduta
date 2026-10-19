package com.agenticai.core.payments.commerce;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * A commerce-mandate check that failed - bad state transition, over-ceiling cart, signature
 * failure. Codes are stable strings (part of the API/audit contract), not an enum, so they travel
 * cleanly in JSON.
 */
public class CommerceMandateException extends RuntimeException implements HttpStatusMapped {

    private final String code;

    public CommerceMandateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** HTTP mapping of {@link #getCode()}, answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return switch (code) {
            case "NOT_FOUND" -> 404;
            case "WRONG_STATE",
                    "OVER_CEILING",
                    "INTENT_INACTIVE",
                    "INTENT_EXPIRED",
                    "CART_EXPIRED",
                    "CAPTURE_FAILED" ->
                    409;
            default -> 400;
        };
    }

    @Override
    public String errorCode() {
        return code;
    }
}
