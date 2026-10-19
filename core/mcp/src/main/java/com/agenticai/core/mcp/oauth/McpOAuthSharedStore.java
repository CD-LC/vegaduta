package com.agenticai.core.mcp.oauth;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The pieces of {@link McpOAuthService} and {@link OAuthProviderService} that
 * ADR-0014 section 5 flagged as multi-replica unsafe, behind {@code
 * app.mcp.oauth.store=memory|redis} (default {@code memory} - the
 * single-replica behaviour, Redis never resolved):
 *
 * <ul>
 *   <li><b>single-use state nonces</b> ({@link #claimNonce}) - the consumed set
 *       of {@link McpOAuthStateCodec} / {@link OAuthStateCodec}. Without a
 *       shared set a callback replayed on another replica is not rejected. The
 *       durable connection rows ({@link McpOAuthConnectionStore}, V424) are
 *       already shared; this is not about them.</li>
 *   <li><b>the access-token cache</b> ({@link #token} / {@link #putToken} /
 *       {@link #removeToken}) - refreshable access tokens. In Redis mode the
 *       token is {@link com.agenticai.core.crypto.EncryptionService
 *       EncryptionService}-encrypted before it is written: the store honours
 *       the same "no plaintext secret at rest" rule as the JPA store.</li>
 *   <li><b>the refresh single-flight</b> ({@link #withRefreshLock}) - without a
 *       cross-replica guard two replicas can refresh the same rotating refresh
 *       token (Zoom, Linear, Notion) in parallel and burn it.</li>
 * </ul>
 *
 * Failure posture (matches {@code RedisMcpOwnershipRegistry} and {@code
 * AbuseThrottleService}): reads and the lock fail OPEN to this replica's
 * in-memory copy when the store is down - the worst case is the pre-existing
 * redundant refresh. {@link #claimNonce} fails CLOSED, because an unverifiable
 * single-use claim must never pass as first use.
 */
public interface McpOAuthSharedStore {

    /** Nonce namespace of the generic remote-MCP flow ({@link McpOAuthStateCodec}). */
    String STATE_KIND_REMOTE = "remote";

    /** Nonce namespace of the provider flow ({@link OAuthStateCodec}). */
    String STATE_KIND_PROVIDER = "provider";

    /**
     * Atomically marks an OAuth state nonce consumed.
     *
     * @return {@code true} on first use; {@code false} on a replay inside the nonce's lifetime
     * @throws OAuthStateException when the backing store cannot answer (single-use is a security
     *                            property and fails closed, not open)
     */
    boolean claimNonce(String kind, String nonce, Instant expiresAt);

    /** A cached live access token ({@code validUntil} null = does not expire). */
    record CachedToken(String accessToken, Instant validUntil) {
        public boolean validAt(Instant now) {
            return validUntil == null || now.isBefore(validUntil);
        }
    }

    Optional<CachedToken> token(String tenantId, String key);

    void putToken(String tenantId, String key, CachedToken token);

    void removeToken(String tenantId, String key);

    /**
     * Single-flight per {@code (tenantId, key)}: refresh and disconnect calls for one connection
     * serialize so a rotating refresh token is never used twice in parallel. In Redis mode the
     * exclusion is cross-replica; in memory mode it is this JVM only. Reentrant for the calling
     * thread (a disconnect's quiet access-token lookup re-enters the same key).
     */
    <T> T withRefreshLock(String tenantId, String key, Supplier<T> action);
}
