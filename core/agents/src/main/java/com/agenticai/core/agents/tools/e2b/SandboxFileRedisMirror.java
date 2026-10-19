package com.agenticai.core.agents.tools.e2b;

import com.agenticai.core.sdlc.dto.SandboxFile;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.replica.BoundedRedisCall;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis write-through copy of the data files {@link SandboxFileRegistry} holds per chat session
 * ({@code app.sandbox.data-analysis.store=redis}), so a follow-up turn that lands on ANOTHER core
 * replica can still mount the files the user attached on the first turn.
 *
 * <p>One Redis hash per session, {@code sandbox:files:<tenantId>:<agentId>:<sessionId>}, field =
 * sanitized file name, value = Base64 of the bytes, with the registry's TTL (refreshed on every
 * WRITE only: a read never extends it, so an abandoned session cannot be kept alive in Redis by
 * other replicas polling it).
 *
 * <p>Redis memory is bounded here (it is shared with rate limits, MCP leases, chat cancel and A2A
 * tasks):
 *
 * <ul>
 *   <li>per session: at most {@code maxFilesPerSession} fields (checked with {@code HLEN} before a
 *       write);
 *   <li>per tenant and platform-wide: two {@code INCRBY} byte counters ({@code
 *       sandbox:files-bytes:...}) reserved before a write and given back when a session's hash is
 *       deleted. They count RAW bytes (Base64 in Redis is about a third larger), carry the same TTL
 *       as the hashes and are refreshed on each write, so they can only expire after every hash
 *       they cover has. A write that would exceed either budget is skipped (the file then exists on
 *       the receiving replica only, which is the default behaviour);
 *   <li>when the registry evicts a session it wrote for (per-tenant, platform or byte budget), it
 *       deletes the hash here too ({@link #deleteAsync}). A TTL expiry is left to Redis itself.
 * </ul>
 *
 * Assumption to confirm with the Redis owner: every key written here has a TTL, so a {@code
 * volatile-*} eviction policy is able to reclaim them under memory pressure; the large values still
 * cost single-threaded Redis time per write, which is why writes are asynchronous.
 *
 * <p>Name collisions across replicas: two replicas that both receive an upload with the same
 * sanitized name for one session cannot see each other's local list, so the write uses {@code
 * HSETNX} and, when the name is taken, the next free {@code name_2.ext}, {@code name_3.ext}... and
 * tells the registry ({@link Renamer}) so the receiving replica's local copy carries the name Redis
 * (and therefore every other replica) uses. A first upload is never overwritten.
 *
 * <ul>
 *   <li>Writes are asynchronous on one bounded daemon thread, so an upload never waits on Redis and
 *       a hung Redis cannot hold the registry's monitor. A full queue drops the mirror write (WARN
 *       once): that file then only exists on the replica that received it, which is today's
 *       behaviour.
 *   <li>Reads ({@link #loadMissing}) happen on the {@code run_code} tool path only: one {@code
 *       HKEYS} (names only), then {@code HGET} for just the names this replica does not already
 *       hold, all under one {@link #READ_TIMEOUT_MILLIS} ms bound ({@link BoundedRedisCall};
 *       Lettuce's own command timeout is 60 s).
 *   <li>Every Redis failure returns "nothing" (null from {@link #loadMissing}) and logs once; the
 *       registry keeps working from its local copy.
 * </ul>
 */
final class SandboxFileRedisMirror {

    private static final Logger log = LoggerFactory.getLogger(SandboxFileRedisMirror.class);

    static final String KEY_PREFIX = "sandbox:files:";
    static final String BUDGET_PREFIX = "sandbox:files-bytes:";
    static final String PLATFORM_BUDGET_KEY = BUDGET_PREFIX + "all";

    /**
     * Whole-read bound: one HKEYS plus up to a few HGETs of up to 10 MB each (Base64), in the same
     * region.
     */
    static final long READ_TIMEOUT_MILLIS = 2_000L;

    private static final int QUEUE_CAPACITY = 32;
    private static final int MAX_NAME_ATTEMPTS = 20;

    /**
     * Called on the writer thread when the Redis name differs from the one the registry assigned
     * locally.
     */
    @FunctionalInterface
    interface Renamer {
        void renamed(String from, String to);
    }

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final int maxFilesPerSession;
    private final long tenantBudgetBytes;
    private final long platformBudgetBytes;
    private final ThreadPoolExecutor writer;
    private final BoundedRedisCall reads = new BoundedRedisCall("sandbox-file-mirror-read");
    private final AtomicBoolean warned = new AtomicBoolean();
    private final AtomicBoolean budgetWarned = new AtomicBoolean();

    SandboxFileRedisMirror(StringRedisTemplate redis, Duration ttl) {
        this(
                redis,
                ttl,
                SandboxFileRegistry.DEFAULT_MAX_FILES_PER_SESSION,
                SandboxFileRegistry.DEFAULT_MAX_TENANT_BYTES,
                SandboxFileRegistry.DEFAULT_MAX_TOTAL_BYTES);
    }

    SandboxFileRedisMirror(
            StringRedisTemplate redis,
            Duration ttl,
            int maxFilesPerSession,
            long tenantBudgetBytes,
            long platformBudgetBytes) {
        this.redis = redis;
        this.ttl = ttl;
        this.maxFilesPerSession = Math.max(1, maxFilesPerSession);
        this.tenantBudgetBytes = Math.max(1L, tenantBudgetBytes);
        this.platformBudgetBytes = Math.max(1L, platformBudgetBytes);
        this.writer =
                TenantAwareExecutors.newThreadPool(
                        1,
                        1,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                        TenantAwareExecutors.threadFactory("sandbox-file-mirror", true));
        this.writer.allowCoreThreadTimeOut(true);
    }

    static String redisKey(String tenant, String sessionKey) {
        return KEY_PREFIX + tenant + ":" + sessionKey;
    }

    static String tenantBudgetKey(String tenant) {
        return BUDGET_PREFIX + "tenant:" + tenant;
    }

    /** Queues one file for Redis; never blocks and never throws. */
    void putAsync(String tenant, String sessionKey, String name, byte[] bytes, Renamer renamer) {
        try {
            writer.execute(() -> putNow(tenant, sessionKey, name, bytes, renamer));
        } catch (RejectedExecutionException e) {
            warnOnce("mirror queue full, the file stays on this replica only", null);
        }
    }

    /**
     * Queues deleting a session's hash (the registry evicted its local copy); never blocks and
     * never throws.
     */
    void deleteAsync(String tenant, String sessionKey, long reservedBytes) {
        try {
            writer.execute(() -> deleteNow(tenant, sessionKey, reservedBytes));
        } catch (RejectedExecutionException e) {
            warnOnce("mirror queue full, an evicted session's copy is left to expire", null);
        }
    }

    /** The write itself; package-private so tests can run it inline. */
    void putNow(String tenant, String sessionKey, String name, byte[] bytes, Renamer renamer) {
        String key = redisKey(tenant, sessionKey);
        boolean reserved = false;
        boolean created = false;
        try {
            reserved = reserve(tenant, bytes.length);
            if (!reserved) {
                if (budgetWarned.compareAndSet(false, true)) {
                    log.warn(
                            "Data-analysis sandbox file mirror: the Redis byte budget is used up - files over it stay "
                                    + "on the receiving replica only");
                }
                return;
            }
            Long existing = redis.opsForHash().size(key);
            if (existing != null && existing >= maxFilesPerSession) {
                reserved = false;
                giveBack(tenant, bytes.length);
                return;
            }
            String encoded = Base64.getEncoder().encodeToString(bytes);
            String finalName = null;
            for (int attempt = 1; attempt <= MAX_NAME_ATTEMPTS && finalName == null; attempt++) {
                String candidate =
                        attempt == 1 ? name : SandboxFileRegistry.suffixed(name, attempt);
                if (Boolean.TRUE.equals(redis.opsForHash().putIfAbsent(key, candidate, encoded))) {
                    finalName = candidate;
                    created = true;
                }
            }
            if (finalName == null) {
                reserved = false;
                giveBack(tenant, bytes.length);
                return;
            }
            redis.expire(key, ttl);
            if (!finalName.equals(name) && renamer != null) {
                renamer.renamed(name, finalName);
            }
        } catch (RuntimeException e) {
            if (created) {
                deleteQuietly(key); // never leave a hash without its TTL behind
            }
            if (reserved) {
                giveBackQuietly(tenant, bytes.length);
            }
            warnOnce("could not write a data file to Redis", e);
        }
    }

    /**
     * Removes a session's hash and gives its reserved bytes back; package-private so tests can run
     * it inline.
     */
    void deleteNow(String tenant, String sessionKey, long reservedBytes) {
        try {
            redis.delete(redisKey(tenant, sessionKey));
            if (reservedBytes > 0) {
                giveBack(tenant, reservedBytes);
            }
        } catch (RuntimeException e) {
            warnOnce("could not delete an evicted session's data files from Redis", e);
        }
    }

    /**
     * The session's files in Redis whose names are not in {@code haveNames}. Empty when there is
     * nothing new; null when Redis could not be used or did not answer in time (the caller keeps
     * what it has). Does NOT refresh the TTL.
     */
    List<SandboxFile> loadMissing(String tenant, String sessionKey, Set<String> haveNames) {
        String key = redisKey(tenant, sessionKey);
        try {
            return reads.call(
                    READ_TIMEOUT_MILLIS,
                    () -> {
                        Set<Object> names = redis.opsForHash().keys(key);
                        if (names == null || names.isEmpty()) {
                            return List.<SandboxFile>of();
                        }
                        List<SandboxFile> found = new ArrayList<>();
                        for (Object nameObject : names) {
                            String name = String.valueOf(nameObject);
                            if (haveNames.contains(name)) {
                                continue;
                            }
                            Object encoded = redis.opsForHash().get(key, name);
                            if (encoded != null) {
                                found.add(
                                        new SandboxFile(
                                                name,
                                                Base64.getDecoder()
                                                        .decode(String.valueOf(encoded))));
                            }
                        }
                        return found;
                    });
        } catch (RuntimeException e) {
            warnOnce("could not read data files from Redis", e);
            return null;
        }
    }

    /**
     * Reserves {@code size} raw bytes against the tenant and platform budgets; false when either
     * would be exceeded.
     */
    private boolean reserve(String tenant, long size) {
        String tenantKey = tenantBudgetKey(tenant);
        boolean tenantApplied = false;
        boolean platformApplied = false;
        try {
            Long tenantTotal = redis.opsForValue().increment(tenantKey, size);
            if (tenantTotal == null) {
                return false;
            }
            tenantApplied = true;
            redis.expire(tenantKey, ttl);
            if (tenantTotal > tenantBudgetBytes) {
                redis.opsForValue().decrement(tenantKey, size);
                return false;
            }
            Long platformTotal = redis.opsForValue().increment(PLATFORM_BUDGET_KEY, size);
            if (platformTotal == null) {
                redis.opsForValue().decrement(tenantKey, size);
                return false;
            }
            platformApplied = true;
            redis.expire(PLATFORM_BUDGET_KEY, ttl);
            if (platformTotal > platformBudgetBytes) {
                redis.opsForValue().decrement(PLATFORM_BUDGET_KEY, size);
                redis.opsForValue().decrement(tenantKey, size);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            // Undo what was applied (best effort), then let the caller treat the write as failed.
            try {
                if (platformApplied) {
                    redis.opsForValue().decrement(PLATFORM_BUDGET_KEY, size);
                }
                if (tenantApplied) {
                    redis.opsForValue().decrement(tenantKey, size);
                }
            } catch (RuntimeException ignored) {
                // the leaked reservation ends with the counter's TTL
            }
            throw e;
        }
    }

    /** Gives reserved bytes back to both budgets, never leaving a negative counter behind. */
    private void giveBack(String tenant, long size) {
        decrementClamped(tenantBudgetKey(tenant), size);
        decrementClamped(PLATFORM_BUDGET_KEY, size);
    }

    private void giveBackQuietly(String tenant, long size) {
        try {
            giveBack(tenant, size);
        } catch (RuntimeException ignored) {
            // the leaked reservation ends with the counter's TTL
        }
    }

    private void decrementClamped(String key, long size) {
        Long left = redis.opsForValue().decrement(key, size);
        if (left != null && left < 0) {
            redis.opsForValue().increment(key, -left);
        }
    }

    private void deleteQuietly(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException ignored) {
            // nothing more to do; the hash ends with Redis's own eviction if it has no TTL
        }
    }

    /**
     * Test seam: returns once every write queued so far has run (the writer is a single FIFO
     * thread).
     */
    void awaitQueuedWrites() throws Exception {
        writer.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    void shutdown() {
        writer.shutdownNow();
        reads.shutdown();
    }

    private void warnOnce(String what, RuntimeException cause) {
        if (warned.compareAndSet(false, true)) {
            log.warn(
                    "Data-analysis sandbox file mirror: {} - continuing with this replica's local files only",
                    what,
                    cause);
        }
    }
}
