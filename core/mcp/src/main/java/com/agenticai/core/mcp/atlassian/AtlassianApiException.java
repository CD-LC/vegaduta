package com.agenticai.core.mcp.atlassian;

/** Atlassian's OAuth/DCR endpoints returned an error or were unreachable - same role as {@code XApiException}. */
public class AtlassianApiException extends RuntimeException {

    /**
     * The OAuth {@code error} code from a parsed error response body (RFC
     * 6749 §5.2, e.g. {@code "invalid_grant"}), or null when the failure
     * never got a real Atlassian response to parse in the first place (a
     * network-level failure, timeout, or a response whose body wasn't the
     * expected JSON error shape). Callers MUST NOT treat null as "not
     * invalid_grant, therefore safe to retry" AND "definitely not
     * invalid_grant either" in the same breath - null specifically means
     * "unknown", not "confirmed transient". See {@code
     * AtlassianOAuthService#currentAccessToken}'s javadoc for the one place
     * this distinction is load-bearing (whether to mark a tenant's
     * connection permanently revoked).
     */
    private final String oauthErrorCode;

    public AtlassianApiException(String message) {
        this(message, null, null);
    }

    public AtlassianApiException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public AtlassianApiException(String message, Throwable cause, String oauthErrorCode) {
        super(message, cause);
        this.oauthErrorCode = oauthErrorCode;
    }

    public String getOauthErrorCode() {
        return oauthErrorCode;
    }
}
