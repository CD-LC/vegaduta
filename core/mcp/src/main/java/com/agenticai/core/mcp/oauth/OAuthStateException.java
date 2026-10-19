package com.agenticai.core.mcp.oauth;

/** The OAuth {@code state} blob is unusable. {@link #reason()} is a stable code the callback maps to a UI message. */
public class OAuthStateException extends McpOAuthException {

    /** Stable reason codes. */
    public static final String INVALID = "invalid_state";
    public static final String EXPIRED = "expired_state";
    public static final String REPLAYED = "replayed_state";
    public static final String PROVIDER_MISMATCH = "provider_mismatch";
    /** Generic remote-MCP flow only: the callback arrived on a session of another tenant/user than the one that started it. */
    public static final String TENANT_MISMATCH = "tenant_mismatch";
    /** Generic remote-MCP flow only: RFC 9207 - the {@code iss} response parameter is wrong, or missing when the server promised it. */
    public static final String ISSUER_MISMATCH = "issuer_mismatch";
    /** Generic remote-MCP flow only: the state names a server this tenant has no pending connection for. */
    public static final String NO_PENDING_CONNECTION = "no_pending_connection";

    private final String reason;

    public OAuthStateException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
