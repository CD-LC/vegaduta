package com.agenticai.core.payments.wallet;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * A wallet operation that failed - insufficient funds, frozen wallet, bad amount. Codes are stable
 * strings (part of the API/audit contract), not an enum, so they travel cleanly in JSON.
 */
public class WalletException extends RuntimeException implements HttpStatusMapped {

    private final String code;

    public WalletException(String code, String message) {
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
            case "WALLET_NOT_FOUND" -> 404;
            case "WALLET_NOT_ACTIVE" -> 409;
            case "INSUFFICIENT_FUNDS" -> 402;
            default -> 400;
        };
    }

    @Override
    public String errorCode() {
        return code;
    }
}
