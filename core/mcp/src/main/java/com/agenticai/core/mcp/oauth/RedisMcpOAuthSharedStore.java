package com.agenticai.core.mcp.oauth;

import com.agenticai.core.crypto.EncryptionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@code app.mcp.oauth.store=redis} - the multi-replica answer to ADR-0014
 * section 5. Every key lives under {@code mcp:oauth:}:
 *
 * <ul>
 *   <li>{@code mcp:oauth:state:{kind}:{nonce}} - written {@code SET NX EX}, so
 *       consuming a state is atomic across replicas and a replay on another pod
 *       is rejected. FAILS CLOSED: if Redis cannot answer, the callback is
 *       refused (an unverifiable single-use claim must never pass as first
 *       use); the caller retries the whole connect.</li>
 *   <li>{@code mcp:oauth:token:{tenant}:{sha256(key)}} - the cached access
 *       token, {@link EncryptionService}-encrypted before it leaves the JVM
 *       (the "no plaintext secret at rest" rule also covers Valkey). The value
 *       is {@code <validUntilEpochMs|->|<ciphertext>} and the key's TTL is the
 *       token's own validity (or {@link #MAX_TOKEN_TTL} when it does not
 *       expire), so entries clean themselves up.</li>
 *   <li>{@code mcp:oauth:lock:{tenant}:{sha256(key)}} - the refresh
 *       single-flight: {@code SET NX PX} with a unique owner token, released by
 *       a compare-and-delete Lua script so an expired-and-retaken lock is never
 *       freed by its previous holder. Bounded wait ({@link #LOCK_WAIT}), then
 *       the caller proceeds under this JVM's lock only - fail-open, the same
 *       posture as {@code RedisMcpOwnershipRegistry}.</li>
 * </ul>
 *
 * A per-replica in-memory shadow ({@link #local}) is written on every
 * {@link #putToken} / {@link #removeToken} and answers reads while Redis is
 * down; a Redis-side miss also clears the shadow so a disconnect on another
 * replica cannot leave a stale token behind here. Redis keys embed the tenant
 * id (tenant-scoped like {@code abuse:throttle:}) and hash the resource URL /
 * provider id, keeping keys bounded and free of user-typed text.
 */
public final class RedisMcpOAuthSharedStore implements McpOAuthSharedStore {

    private static final Logger log = LoggerFactory.getLogger(RedisMcpOAuthSharedStore.class);

    static final String STATE_PREFIX = "mcp:oauth:state:";
    static final String TOKEN_PREFIX = "mcp:oauth:token:";
    static final String LOCK_PREFIX = "mcp:oauth:lock:";

    /** Lease on the cross-replica refresh lock; self-releases if the holder dies mid-refresh. */
    static final Duration LOCK_TTL = Duration.ofSeconds(30);

    /** How long a caller waits for another replica's refresh before proceeding under its JVM lock only. */
    static final Duration LOCK_WAIT = Duration.ofSeconds(15);

    static final long LOCK_POLL_MILLIS = 50;

    /** TTL for a cached access token that carries no expiry of its own. */
    static final Duration MAX_TOKEN_TTL = Duration.ofHours(1);

    /** Compare-and-delete: only the lock's current owner may free it. */
    private static final RedisScript<Long> RELEASE_SCRIPT =
        new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final EncryptionService encryption;
    private final Clock clock;
    /** Fail-open read shadow and the write-through copy for Redis outages. */
    private final InMemoryMcpOAuthSharedStore local;
    /** Per-JVM outer half of the refresh lock: makes {@link #withRefreshLock} reentrant per thread. */
    private final ConcurrentHashMap<String, ReentrantLock> lockHolders = new ConcurrentHashMap<>();
    private final Duration lockWait;
    private final long lockPollMillis;
    private final AtomicBoolean warned = new AtomicBoolean();

    public RedisMcpOAuthSharedStore(StringRedisTemplate redis, EncryptionService encryption, Clock clock) {
        this(redis, encryption, clock, new InMemoryMcpOAuthSharedStore(clock), LOCK_WAIT, LOCK_POLL_MILLIS);
    }

    /** Test seam: the local shadow and the lock wait are injectable. */
    RedisMcpOAuthSharedStore(
        StringRedisTemplate redis, EncryptionService encryption, Clock clock, InMemoryMcpOAuthSharedStore local,
        Duration lockWait, long lockPollMillis) {
        this.redis = redis;
        this.encryption = encryption;
        this.clock = clock;
        this.local = local;
        this.lockWait = lockWait;
        this.lockPollMillis = lockPollMillis;
    }

    // --------------------------------------------------- single-use state

    @Override
    public boolean claimNonce(String kind, String nonce, Instant expiresAt) {
        Duration ttl = ttlUntil(expiresAt);
        if (ttl == null) {
            // Already expired: decode() refuses the state before consume() ever runs.
            return true;
        }
        try {
            return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(STATE_PREFIX + kind + ":" + nonce, "1", ttl));
        } catch (RuntimeException e) {
            warnOnce(
                "MCP OAuth shared store: Redis unavailable while consuming a state nonce -"
                    + " refusing the callback (fail closed)");
            throw new OAuthStateException(OAuthStateException.INVALID,
                "The OAuth state could not be verified - please try connecting again.");
        }
    }

    // ------------------------------------------------------- token cache

    @Override
    public Optional<CachedToken> token(String tenantId, String key) {
        try {
            String raw = redis.opsForValue().get(tokenKey(tenantId, key));
            if (raw == null) {
                // A delete on another replica must win over this replica's shadow copy.
                local.removeToken(tenantId, key);
                return Optional.empty();
            }
            int sep = raw.indexOf('|');
            if (sep < 0) {
                return Optional.empty();
            }
            Instant validUntil = "-".equals(raw.substring(0, sep))
                ? null : Instant.ofEpochMilli(Long.parseLong(raw.substring(0, sep)));
            return Optional.of(new CachedToken(encryption.decrypt(raw.substring(sep + 1)), validUntil));
        } catch (RuntimeException e) {
            warnOnce(
                "MCP OAuth shared store: Redis unavailable while reading a cached access token -"
                    + " using this replica's copy");
            return local.token(tenantId, key);
        }
    }

    @Override
    public void putToken(String tenantId, String key, CachedToken token) {
        local.putToken(tenantId, key, token);
        Duration ttl = token.validUntil() == null ? MAX_TOKEN_TTL : ttlUntil(token.validUntil());
        try {
            if (ttl == null) {
                // Already expired: same as the in-memory lazy expiry - nothing to keep.
                redis.delete(tokenKey(tenantId, key));
                return;
            }
            String value = (token.validUntil() == null ? "-" : Long.toString(token.validUntil().toEpochMilli()))
                + "|" + encryption.encrypt(token.accessToken());
            redis.opsForValue().set(tokenKey(tenantId, key), value, ttl);
        } catch (RuntimeException e) {
            warnOnce(
                "MCP OAuth shared store: a cached access token could not be written to Redis -"
                    + " this replica's copy only");
        }
    }

    @Override
    public void removeToken(String tenantId, String key) {
        local.removeToken(tenantId, key);
        try {
            redis.delete(tokenKey(tenantId, key));
        } catch (RuntimeException e) {
            warnOnce(
                "MCP OAuth shared store: a cached access token could not be deleted from Redis -"
                    + " it lapses at its TTL");
        }
    }

    // -------------------------------------------------- refresh lock

    @Override
    public <T> T withRefreshLock(String tenantId, String key, Supplier<T> action) {
        String lockKey = LOCK_PREFIX + tenantId + ":" + fingerprint(key);
        ReentrantLock localLock = lockHolders.computeIfAbsent(lockKey, k -> new ReentrantLock());
        localLock.lock();
        try {
            String owner = null;
            if (localLock.getHoldCount() == 1) {
                // Fresh acquire on this thread: take the cross-replica lease too. A re-entrant call
                // (disconnect -> quiet token lookup -> refresh lock on the same key) must NOT
                // re-acquire or it would deadlock on its own SET NX.
                owner = acquire(lockKey);
            }
            try {
                return action.get();
            } finally {
                if (owner != null) {
                    release(lockKey, owner);
                }
            }
        } finally {
            localLock.unlock();
        }
    }

    /**
     * @return the owner token when the cross-replica lease was taken, or null to proceed under the
     *         JVM lock only (Redis down, or the lease stayed taken past {@link #LOCK_WAIT})
     */
    private String acquire(String lockKey) {
        String owner = UUID.randomUUID().toString();
        // Monotonic deadline: the business clock may not track wall time.
        long deadline = System.nanoTime() + lockWait.toNanos();
        try {
            while (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, owner, LOCK_TTL))) {
                if (System.nanoTime() >= deadline) {
                    warnOnce(
                        "MCP OAuth shared store: the refresh lock stayed taken past " + lockWait
                            + " - proceeding under this replica's lock only");
                    return null;
                }
                try {
                    Thread.sleep(lockPollMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return owner;
        } catch (RuntimeException e) {
            warnOnce(
                "MCP OAuth shared store: Redis unavailable while taking the refresh lock -"
                    + " this replica's lock only");
            return null;
        }
    }

    private void release(String lockKey, String owner) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(lockKey), owner);
        } catch (RuntimeException e) {
            warnOnce(
                "MCP OAuth shared store: a refresh lock could not be released - it lapses at its TTL");
        }
    }

    // ----------------------------------------------------------- helpers

    /** @return null when the instant is already in the past (or now). */
    private Duration ttlUntil(Instant expiry) {
        Duration ttl = Duration.between(clock.instant(), expiry);
        return ttl.isNegative() || ttl.isZero() ? null : ttl;
    }

    private static String tokenKey(String tenantId, String key) {
        return TOKEN_PREFIX + tenantId + ":" + fingerprint(key);
    }

    /** Bounded, URL-free key material: the resource URL can be long and is user-typed text. */
    static String fingerprint(String value) {
        try {
            byte[] digest =
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void warnOnce(String message) {
        if (warned.compareAndSet(false, true)) {
            log.warn("{} (logged once)", message);
        }
    }
}
