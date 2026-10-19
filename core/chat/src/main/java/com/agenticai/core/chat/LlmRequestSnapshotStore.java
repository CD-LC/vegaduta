package com.agenticai.core.chat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded, tenant-scoped, in-memory ring of recent {@link LlmRequestSnapshot}s,
 * keyed by (tenant, session id). A workflow agent node's session id IS the run
 * id, so one store serves both the playground and workflow runs.
 *
 * <p>Deliberately not durable: prompts hold user data, and the platform pins
 * prompt logging off. Entries expire after {@link #TTL}; the whole store is
 * capped by session count and per-session depth so it cannot grow unbounded.
 * Static singleton because {@link LlmMetricsAdvisor} is built by hand in every
 * provider factory, not wired as a bean.
 */
public final class LlmRequestSnapshotStore {

    public static final LlmRequestSnapshotStore INSTANCE = new LlmRequestSnapshotStore();

    static final Duration TTL = Duration.ofHours(6);
    private static final int MAX_SESSIONS = 300;
    private static final int MAX_PER_SESSION = 80;

    private final Map<String, Deque<LlmRequestSnapshot>> bySession = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<LlmRequestSnapshot>> eldest) {
            return size() > MAX_SESSIONS;
        }
    };

    private LlmRequestSnapshotStore() {}

    private static String key(String tenantId, String sessionId) {
        return (tenantId == null ? "-" : tenantId) + "|" + sessionId;
    }

    public synchronized void add(String tenantId, LlmRequestSnapshot snapshot) {
        if (snapshot.sessionId() == null) {
            return;
        }
        Deque<LlmRequestSnapshot> ring = bySession.computeIfAbsent(key(tenantId, snapshot.sessionId()), k -> new ArrayDeque<>());
        ring.addLast(snapshot);
        while (ring.size() > MAX_PER_SESSION) {
            ring.removeFirst();
        }
    }

    /**
     * Attaches provider-reported prompt tokens to the snapshot with this exact id. Matches by id so
     * concurrent or multi-iteration calls in one session cannot cross-attribute; attaches once and
     * never overwrites an existing value (a stream can finalize more than once).
     */
    public synchronized void attachPromptTokens(String tenantId, String sessionId, String snapshotId, Long tokens) {
        if (sessionId == null || snapshotId == null || tokens == null) {
            return;
        }
        Deque<LlmRequestSnapshot> ring = bySession.get(key(tenantId, sessionId));
        if (ring == null) {
            return;
        }
        // Newest first: the call being finalized is almost always at the tail.
        java.util.Iterator<LlmRequestSnapshot> it = ring.descendingIterator();
        List<LlmRequestSnapshot> tail = new ArrayList<>();
        while (it.hasNext()) {
            LlmRequestSnapshot s = it.next();
            if (snapshotId.equals(s.id())) {
                if (s.providerPromptTokens() != null) {
                    return;
                }
                // Rebuild only the entries from the tail down to the match.
                for (int k = 0; k <= tail.size(); k++) {
                    ring.removeLast();
                }
                ring.addLast(s.withProviderPromptTokens(tokens));
                for (int k = tail.size() - 1; k >= 0; k--) {
                    ring.addLast(tail.get(k));
                }
                return;
            }
            tail.add(s);
        }
    }

    /** Oldest first. Optionally narrowed to one agent and a time window (a workflow node's span). */
    public synchronized List<LlmRequestSnapshot> list(
        String tenantId, String sessionId, String agentId, Instant from, Instant to
    ) {
        Deque<LlmRequestSnapshot> ring = bySession.get(key(tenantId, sessionId));
        if (ring == null) {
            return List.of();
        }
        Instant cutoff = Instant.now().minus(TTL);
        ring.removeIf(s -> s.at().isBefore(cutoff));
        List<LlmRequestSnapshot> out = new ArrayList<>();
        for (LlmRequestSnapshot s : ring) {
            if (agentId != null && !agentId.equals(s.agentId())) {
                continue;
            }
            if (from != null && s.at().isBefore(from)) {
                continue;
            }
            if (to != null && s.at().isAfter(to)) {
                continue;
            }
            out.add(s);
        }
        return out;
    }
}
