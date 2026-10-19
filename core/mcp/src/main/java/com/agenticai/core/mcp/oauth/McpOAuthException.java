package com.agenticai.core.mcp.oauth;

/**
 * Failure anywhere in the generic MCP OAuth flow (discovery, registration,
 * code exchange, refresh). {@link #getOauthErrorCode()} carries the RFC 6749
 * section 5.2 {@code error} value when a token endpoint returned one, so callers
 * can tell a permanent {@code invalid_grant} from a transient failure.
 */
public class McpOAuthException extends RuntimeException {

    private final String oauthErrorCode;

    public McpOAuthException(String message) {
        this(message, null, null);
    }

    public McpOAuthException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public McpOAuthException(String message, Throwable cause, String oauthErrorCode) {
        super(message, cause);
        this.oauthErrorCode = oauthErrorCode;
    }

    public String getOauthErrorCode() {
        return oauthErrorCode;
    }
}
