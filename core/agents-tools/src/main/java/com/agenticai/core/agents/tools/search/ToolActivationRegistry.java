package com.agenticai.core.agents.tools.search;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Remembers which deferred tools a conversation has already searched for, so the next turn of the
 * same session can offer them directly instead of making the model search again.
 *
 * <p>Best-effort by design: it is a latency/token optimisation, never a source of truth. The
 * backing {@link ToolActivationStore} is the in-memory LRU by default (a restart, an eviction or a
 * second replica simply means the model searches once more) and Redis when {@code
 * app.tools.search.activation-store} selects it (replica-safe). The key is built by the caller
 * from tenant + agent + session, so one tenant's activations can never be read back under
 * another's key.
 */
public final class ToolActivationRegistry {

    /** Default time a session's activations survive without being touched. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(2);

    /** Default LRU cap on remembered sessions. */
    public static final int DEFAULT_MAX_SESSIONS = 2_000;

    /** Hard ceiling on remembered names per session, whatever the caller asks for. */
    static final int MAX_NAMES_PER_SESSION = 32;

    private final ToolActivationStore store;
    private final Duration ttl;

    public ToolActivationRegistry() {
        this(new MemoryToolActivationStore(DEFAULT_MAX_SESSIONS, Clock.systemUTC()), DEFAULT_TTL);
    }

    public ToolActivationRegistry(int maxSessions, Duration ttl, Clock clock) {
        this(new MemoryToolActivationStore(maxSessions, clock), ttl);
    }

    /**
     * A registry over an explicit {@code store} (e.g. {@link RedisToolActivationStore} for a
     * multi-replica deploy); {@code ttl} defaults to {@link #DEFAULT_TTL} when unset or
     * non-positive. A null {@code store} falls back to the in-memory default.
     */
    public ToolActivationRegistry(ToolActivationStore store, Duration ttl) {
        this.store =
                store == null
                        ? new MemoryToolActivationStore(DEFAULT_MAX_SESSIONS, Clock.systemUTC())
                        : store;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    }

    /** Stable per-conversation key; never null. */
    public static String key(String tenantId, Object agentId, String sessionId) {
        return (tenantId == null ? "-" : tenantId)
                + "|"
                + (agentId == null ? "-" : agentId)
                + "|"
                + (sessionId == null ? "-" : sessionId);
    }

    /**
     * Records {@code names} as activated for {@code key}, most recent last, keeping only the newest
     * {@code maxNames} (at most {@value #MAX_NAMES_PER_SESSION}). {@code maxNames <= 0} records
     * nothing.
     */
    public void remember(String key, Collection<String> names, int maxNames) {
        if (key == null || names == null || names.isEmpty() || maxNames <= 0) {
            return;
        }
        store.remember(key, names, Math.min(maxNames, MAX_NAMES_PER_SESSION), ttl);
    }

    /**
     * The newest {@code limit} activated names for {@code key}, oldest first; empty when none or
     * expired.
     */
    public List<String> recall(String key, int limit) {
        if (key == null || limit <= 0) {
            return List.of();
        }
        List<String> names = store.recall(key, limit, ttl);
        return names == null ? List.of() : names;
    }

    /** Number of sessions currently held (for tests and diagnostics). */
    public int sessionCount() {
        return store.sessionCount();
    }
}
