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
 * The default {@link ToolActivationStore}: a bounded in-process map. It is a latency/token
 * optimisation, never a source of truth - a restart, an eviction or a second replica simply means
 * the model searches once more. Bounded two ways (an LRU cap on sessions and a sliding
 * time-to-live) so an unbounded stream of one-off sessions cannot grow it.
 *
 * <p>Thread-safe: every operation takes the map's monitor, and the critical sections are a few map
 * operations on a handful of names.
 */
public final class MemoryToolActivationStore implements ToolActivationStore {

    private static final class SessionActivations {
        final LinkedHashSet<String> names = new LinkedHashSet<>();
        Instant touchedAt;

        SessionActivations(Instant touchedAt) {
            this.touchedAt = touchedAt;
        }
    }

    private final Clock clock;
    private final Map<String, SessionActivations> sessions;

    public MemoryToolActivationStore(int maxSessions, Clock clock) {
        int cap = Math.max(1, maxSessions);
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

    @Override
    public void remember(String key, Collection<String> names, int maxNames, Duration ttl) {
        if (key == null || names == null || names.isEmpty() || maxNames <= 0) {
            return;
        }
        Instant now = clock.instant();
        synchronized (sessions) {
            SessionActivations entry = sessions.get(key);
            if (entry == null || expired(entry, now, ttl)) {
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
            while (entry.names.size() > maxNames) {
                entry.names.remove(entry.names.iterator().next());
            }
            entry.touchedAt = now;
        }
    }

    @Override
    public List<String> recall(String key, int limit, Duration ttl) {
        if (key == null || limit <= 0) {
            return List.of();
        }
        Instant now = clock.instant();
        synchronized (sessions) {
            SessionActivations entry = sessions.get(key);
            if (entry == null) {
                return List.of();
            }
            if (expired(entry, now, ttl)) {
                sessions.remove(key);
                return List.of();
            }
            entry.touchedAt = now;
            List<String> all = new ArrayList<>(entry.names);
            return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
        }
    }

    @Override
    public int sessionCount() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    private static boolean expired(SessionActivations entry, Instant now, Duration ttl) {
        return ttl != null && entry.touchedAt.plus(ttl).isBefore(now);
    }
}
