package com.agenticai.core.agents.tools.search;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Remembers which deferred tools a conversation has already searched for, so the next turn of the
 * same session can offer them directly instead of making the model search again.
 *
 * <p>In-memory and best-effort by design: it is a latency/token optimisation, never a source of
 * truth. A restart, an eviction or a second replica simply means the model searches once more.
 * Bounded two ways (an LRU cap on sessions and a time-to-live) so an unbounded stream of one-off
 * sessions cannot grow it. The key is built by the caller from tenant + agent + session, so one
 * tenant's activations can never be read back under another's key.
 *
 * <p>Thread-safe: every operation takes the map's monitor, and the critical sections are a few map
 * operations on a handful of names.
 */
public final class ToolActivationRegistry {

    /** Default time a session's activations survive without being touched. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(2);

    /** Default LRU cap on remembered sessions. */
    public static final int DEFAULT_MAX_SESSIONS = 2_000;

    /** Hard ceiling on remembered names per session, whatever the caller asks for. */
    static final int MAX_NAMES_PER_SESSION = 32;

    private static final class SessionActivations {
        final LinkedHashSet<String> names = new LinkedHashSet<>();
        Instant touchedAt;

        SessionActivations(Instant touchedAt) {
            this.touchedAt = touchedAt;
        }
    }

    private final Duration ttl;
    private final Clock clock;
    private final Map<String, SessionActivations> sessions;

    public ToolActivationRegistry() {
        this(DEFAULT_MAX_SESSIONS, DEFAULT_TTL, Clock.systemUTC());
    }

    public ToolActivationRegistry(int maxSessions, Duration ttl, Clock clock) {
        int cap = Math.max(1, maxSessions);
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.sessions =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<String, SessionActivations> eldest) {
                        return size() > cap;
                    }
                };
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
        int keep = Math.min(maxNames, MAX_NAMES_PER_SESSION);
        Instant now = clock.instant();
        synchronized (sessions) {
            SessionActivations entry = sessions.get(key);
            if (entry == null || expired(entry, now)) {
                entry = new SessionActivations(now);
                sessions.put(key, entry);
            }
            for (String name : names) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                entry.names.remove(name);
                entry.names.add(name);
            }
            while (entry.names.size() > keep) {
                String oldest = entry.names.iterator().next();
                entry.names.remove(oldest);
            }
            entry.touchedAt = now;
        }
    }

    /**
     * The newest {@code limit} activated names for {@code key}, oldest first; empty when none or
     * expired.
     */
    public List<String> recall(String key, int limit) {
        if (key == null || limit <= 0) {
            return List.of();
        }
        Instant now = clock.instant();
        synchronized (sessions) {
            SessionActivations entry = sessions.get(key);
            if (entry == null) {
                return List.of();
            }
            if (expired(entry, now)) {
                sessions.remove(key);
                return List.of();
            }
            entry.touchedAt = now;
            List<String> all = new ArrayList<>(entry.names);
            return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
        }
    }

    /** Number of sessions currently held (for tests and diagnostics). */
    public int sessionCount() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    private boolean expired(SessionActivations entry, Instant now) {
        return entry.touchedAt.plus(ttl).isBefore(now);
    }
}
