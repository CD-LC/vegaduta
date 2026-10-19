package com.agenticai.core.mcp.oauth;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence SPI for per-tenant, per-MCP-server OAuth connections. Secrets in
 * {@link McpOAuthConnection} are ALREADY encrypted by {@link McpOAuthService}
 * / {@link OAuthProviderService} ({@code EncryptionService}) before they reach a
 * store, so an implementation never sees plaintext tokens.
 *
 * <p>Two implementations ship: {@link InMemoryMcpOAuthConnectionStore} (not
 * durable, lost on restart) and {@link JpaMcpOAuthConnectionStore}
 * ({@code @Primary}, table {@code mcp_oauth_connection}, Flyway V424) - the
 * durable follow-up this interface always documented.
 *
 * <p>Rows for the provider registry ({@link OAuthProviderService}) use the
 * pseudo resource URL {@code "oauth-provider:<id>"} so they never collide with a
 * generic MCP server's real URL.
 */
public interface McpOAuthConnectionStore {

    Optional<McpOAuthConnection> find(String tenantId, String resourceUrl);

    void save(McpOAuthConnection connection);

    void delete(String tenantId, String resourceUrl);

    /**
     * @param encryptedClientSecret null for a public client, and for provider connections (the platform's own
     *                              client secret is read from configuration at use time, never copied per tenant)
     * @param encryptedRefreshToken null until the code exchange has completed, and for providers that issue none
     * @param scope                 the scope requested at authorization time (may be null)
     * @param encryptedAccessToken  only for a token that cannot be refreshed (a provider that issues a non-expiring
     *                              access token and no refresh token); null otherwise - refreshable access tokens
     *                              are cached in process memory only, never persisted
     * @param accessTokenExpiresAt  expiry of {@code encryptedAccessToken}; null when it does not expire
     */
    record McpOAuthConnection(
        String tenantId,
        String resourceUrl,
        String issuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        String clientId,
        String encryptedClientSecret,
        String tokenEndpointAuthMethod,
        String redirectUri,
        String scope,
        String encryptedRefreshToken,
        String encryptedAccessToken,
        Instant accessTokenExpiresAt
    ) {
        /** The pre-provider-registry shape: no persisted access token. */
        public McpOAuthConnection(
            String tenantId, String resourceUrl, String issuer, String authorizationEndpoint, String tokenEndpoint,
            String clientId, String encryptedClientSecret, String tokenEndpointAuthMethod, String redirectUri,
            String scope, String encryptedRefreshToken
        ) {
            this(tenantId, resourceUrl, issuer, authorizationEndpoint, tokenEndpoint, clientId, encryptedClientSecret,
                tokenEndpointAuthMethod, redirectUri, scope, encryptedRefreshToken, null, null);
        }

        public McpOAuthConnection withRefreshToken(String newEncryptedRefreshToken) {
            return new McpOAuthConnection(tenantId, resourceUrl, issuer, authorizationEndpoint, tokenEndpoint, clientId,
                encryptedClientSecret, tokenEndpointAuthMethod, redirectUri, scope, newEncryptedRefreshToken,
                encryptedAccessToken, accessTokenExpiresAt);
        }

        /** Replaces the whole token triple (used when a new consent or a non-refreshable token lands). */
        public McpOAuthConnection withTokens(String newEncryptedRefreshToken, String newEncryptedAccessToken, Instant newAccessTokenExpiresAt) {
            return new McpOAuthConnection(tenantId, resourceUrl, issuer, authorizationEndpoint, tokenEndpoint, clientId,
                encryptedClientSecret, tokenEndpointAuthMethod, redirectUri, scope, newEncryptedRefreshToken,
                newEncryptedAccessToken, newAccessTokenExpiresAt);
        }

        public boolean isConnected() {
            return encryptedRefreshToken != null || encryptedAccessToken != null;
        }

        /**
         * Sentinel stored in {@code accessTokenExpiresAt} (with both tokens null) by the generic remote-MCP flow
         * ({@link McpOAuthService}) when the authorization server permanently rejected the connection: the row -
         * and so the registered client - is kept, so the console can say "needs re-authorization" instead of
         * "never connected", and re-consent reuses the client. Needs no schema change; provider rows never carry it.
         */
        public static final Instant REAUTH_MARKER = Instant.EPOCH;

        /** True for a connection a permanent refresh failure (or an expired non-refreshable token) stripped of its tokens. */
        public boolean needsReauth() {
            return !isConnected() && REAUTH_MARKER.equals(accessTokenExpiresAt);
        }

        /** The same registered client with every token dropped and the {@link #REAUTH_MARKER} set. */
        public McpOAuthConnection markedForReauth() {
            return withTokens(null, null, REAUTH_MARKER);
        }
    }
}
