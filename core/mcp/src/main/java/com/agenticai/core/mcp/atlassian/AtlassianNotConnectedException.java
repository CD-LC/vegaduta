package com.agenticai.core.mcp.atlassian;

/**
 * No tenant has completed the Atlassian OAuth consent yet, or a previously
 * active connection was revoked (explicitly, or because Atlassian
 * permanently rejected the refresh token) - same role as {@code
 * XNotConnectedException}. Surfaces to the tenant via {@code
 * DynamicMcpConnectionManager}'s existing connect-failure diagnosis path
 * (see {@link AtlassianOAuthService#currentAccessToken}).
 */
public class AtlassianNotConnectedException extends RuntimeException {

    public AtlassianNotConnectedException() {
        super("Atlassian is not connected - click \"Connect via Atlassian\" to authorize this tenant.");
    }

    public AtlassianNotConnectedException(String message) {
        super(message);
    }
}
