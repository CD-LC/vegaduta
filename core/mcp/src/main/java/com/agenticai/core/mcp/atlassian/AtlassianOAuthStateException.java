package com.agenticai.core.mcp.atlassian;

/** The OAuth {@code state} value was tampered, malformed, or expired - same role as {@code XOAuthStateException}. */
public class AtlassianOAuthStateException extends RuntimeException {

    public AtlassianOAuthStateException(String message) {
        super(message);
    }
}
