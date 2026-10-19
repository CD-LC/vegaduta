package com.agenticai.core.mcp.oauth;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Default ({@code app.mcp.oauth.store=memory}): exactly the per-JVM behaviour
 * the OAuth services had before this SPI existed - the consumed-nonce set of
 * the two state codecs, each service's token cache and its refresh
 * single-flight. Never touches Redis.
 */
public final class InMemoryMcpOAuthSharedStore implements McpOAuthSharedStore {

    /** kind + nonce -> epoch second after which the entry can be forgotten. */
    private final ConcurrentHashMap<String, Long> nonces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedToken> tokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryMcpOAuthSharedStore() {
        this(Clock.systemUTC());
    }

    /** Test seam and the codec default: expiry sweeping follows the supplied clock. */
    InMemoryMcpOAuthSharedStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean claimNonce(String kind, String nonce, Instant expiresAt) {
        long now = clock.instant().getEpochSecond();
        nonces.values().removeIf(expiry -> expiry < now);
        return nonces.putIfAbsent(kind + "\n" + nonce, expiresAt.getEpochSecond()) == null;
    }

    @Override
    public Optional<CachedToken> token(String tenantId, String key) {
        return Optional.ofNullable(tokens.get(mapKey(tenantId, key)));
    }

    @Override
    public void putToken(String tenantId, String key, CachedToken token) {
        tokens.put(mapKey(tenantId, key), token);
    }

    @Override
    public void removeToken(String tenantId, String key) {
        tokens.remove(mapKey(tenantId, key));
    }

    @Override
    public <T> T withRefreshLock(String tenantId, String key, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(mapKey(tenantId, key), k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static String mapKey(String tenantId, String key) {
        return tenantId + "\n" + key;
    }
}
