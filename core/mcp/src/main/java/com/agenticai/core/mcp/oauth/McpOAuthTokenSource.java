package com.agenticai.core.mcp.oauth;

/**
 * Seam between the remote-MCP connect path (core/agents) and the provider
 * OAuth connections of this package: given a registration's structural
 * {@code oauthProvider} marker and its URL, produce the bearer token to send.
 * Generalises what {@code AtlassianOAuthService#currentAccessToken} does for
 * the one hard-wired Atlassian marker, behind the same call site.
 *
 * <p>Both calls resolve the tenant from the ambient {@code TenantContext}
 * (callers such as {@code McpRemoteHeaders.PerRequestHeaders} re-apply the
 * captured tenant around them).
 */
public interface McpOAuthTokenSource {

    /** True if {@code oauthProviderMarker} is one this source owns (never true for {@code "ATLASSIAN"}). */
    boolean handles(String oauthProviderMarker);

    /**
     * A live access token for the ambient tenant's connection.
     *
     * @param serverUrl the registration's URL; the token is released only when it is on the provider's own MCP host
     * @throws McpOAuthException when not connected, revoked, refresh failed, or {@code serverUrl} is not an allowed host
     */
    String accessTokenFor(String oauthProviderMarker, String serverUrl);
}
