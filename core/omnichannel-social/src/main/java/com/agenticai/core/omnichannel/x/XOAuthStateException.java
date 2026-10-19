package com.agenticai.core.omnichannel.x;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * The {@code state} value X's OAuth callback round-tripped was tampered with, malformed, or expired
 * - same role as {@code GoogleOAuthStateException}.
 */
public class XOAuthStateException extends RuntimeException implements HttpStatusMapped {

    public XOAuthStateException(String message) {
        super(message);
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
