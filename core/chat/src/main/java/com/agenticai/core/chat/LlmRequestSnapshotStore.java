package com.agenticai.core.chat;

import com.agenticai.core.resilience.RateLimitedLog;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded, tenant-scoped ring of recent {@link LlmRequestSnapshot}s, keyed by (tenant, session id). A workflow
 * agent node's session id IS the run id, so one store serves both the playground and workflow runs.
 *
 * <p>Every copy is kept in this JVM (the replica that took it can always show it). With
 * {@code app.llm.request-snapshot-store.mode=redis} (the default) it is also written to a
 * {@link LlmRequestSnapshotBackend} (Redis) with the same time-to-live and per-session depth, so another replica
 * answering the request for the same session finds it, and so does this one after a restart. A read merges the two
 * by snapshot id. The write is queued and done on its own thread: a slow or down Redis never delays a model call,
 * and a copy dropped from a full queue stays in memory on the replica that took it.
 *
 * <p>The copies hold user data (redacted and size-capped by {@link LlmRequestSnapshotter}), so they expire after
 * {@code ttl} everywhere and are never written to a database. Static singleton because {@link LlmMetricsAdvisor}
 * is built by hand in every provider factory, not wired as a bean; {@link LlmRequestSnapshotStoreConfiguration}
 * applies the configuration at startup.
 */
public final class LlmRequestSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(LlmRequestSnapshotStore.class);

    private static final RateLimitedLog BACKEND_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));

    public static final LlmRequestSnapshotStore INSTANCE = new LlmRequestSnapshotStore();

    private volatile Duration ttl;
    private volatile int maxSessions;
    private volatile int maxPerSession;
    private volatile LlmRequestSnapshotBackend backend;
    private volatile ThreadPoolExecutor writer;

    private final Map<String, Deque<LlmRequestSnapshot>> bySession = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<LlmRequestSnapshot>> eldest) {
            return size() > maxSessions;
        }
    };

    private LlmRequestSnapshotStore() {
        this(new LlmRequestSnapshotStoreProperties(), null);
    }

    /** Test seam and the shape {@link #configure} applies: limits from {@code properties}, backend optional. */
    LlmRequestSnapshotStore(LlmRequestSnapshotStoreProperties properties, LlmRequestSnapshotBackend backend) {
        apply(properties, backend);
    }

    /** Replaces the limits and the shared backend (null = this JVM only). Called once at startup. */
    void configure(LlmRequestSnapshotStoreProperties properties, LlmRequestSnapshotBackend backend) {
        apply(properties, backend);
    }

    private synchronized void apply(LlmRequestSnapshotStoreProperties properties, LlmRequestSnapshotBackend newBackend) {
        LlmRequestSnapshotStoreProperties p = properties == null ? new LlmRequestSnapshotStoreProperties() : properties;
        this.ttl = p.getTtl();
        this.maxSessions = p.getMaxSessions();
        this.maxPerSession = p.getMaxPerSession();
        ThreadPoolExecutor old = this.writer;
        this.backend = newBackend;
        this.writer = newBackend == null ? null : newWriter(p.getWriteQueueSize());
        if (old != null) {
            old.shutdown();
        }
    }

    private static ThreadPoolExecutor newWriter(int queueSize) {
        // One daemon thread, a bounded queue, and when it is full the oldest waiting copy is skipped.
        return TenantAwareExecutors.newThreadPool(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueSize),
            TenantAwareExecutors.threadFactory("llm-request-snapshot-writer", true),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    Duration ttl() {
        return ttl;
    }

    private static String key(String tenantId, String sessionId) {
        return (tenantId == null ? "-" : tenantId) + "|" + sessionId;
    }

    public void add(String tenantId, LlmRequestSnapshot snapshot) {
        if (snapshot.sessionId() == null) {
            return;
        }
        synchronized (this) {
            Deque<LlmRequestSnapshot> ring =
                bySession.computeIfAbsent(key(tenantId, snapshot.sessionId()), k -> new ArrayDeque<>());
            ring.addLast(snapshot);
            while (ring.size() > maxPerSession) {
                ring.removeFirst();
            }
        }
        shareAsync(tenantId, snapshot);
    }

    /**
     * Attaches provider-reported prompt tokens to the snapshot with this exact id. Matches by id so
     * concurrent or multi-iteration calls in one session cannot cross-attribute; attaches once and
     * never overwrites an existing value (a stream can finalize more than once).
     */
    public void attachPromptTokens(String tenantId, String sessionId, String snapshotId, Long tokens) {
        if (sessionId == null || snapshotId == null || tokens == null) {
            return;
        }
        LlmRequestSnapshot attached = null;
        synchronized (this) {
            Deque<LlmRequestSnapshot> ring = bySession.get(key(tenantId, sessionId));
            if (ring != null) {
                // Newest first: the call being finalized is almost always at the tail.
                java.util.Iterator<LlmRequestSnapshot> it = ring.descendingIterator();
                List<LlmRequestSnapshot> tail = new ArrayList<>();
                while (it.hasNext()) {
                    LlmRequestSnapshot s = it.next();
                    if (snapshotId.equals(s.id())) {
                        if (s.providerPromptTokens() == null) {
                            // Rebuild only the entries from the tail down to the match.
                            for (int k = 0; k <= tail.size(); k++) {
                                ring.removeLast();
                            }
                            attached = s.withProviderPromptTokens(tokens);
                            ring.addLast(attached);
                            for (int k = tail.size() - 1; k >= 0; k--) {
                                ring.addLast(tail.get(k));
                            }
                        }
                        break;
                    }
                    tail.add(s);
                }
            }
        }
        LlmRequestSnapshotBackend shared = backend;
        ThreadPoolExecutor executor = writer;
        if (shared == null || executor == null) {
            return;
        }
        LlmRequestSnapshot local = attached;
        // FIFO behind the save of the same copy, so the copy exists in the backend when the tokens are attached.
        executor.execute(() -> {
            try {
                if (local != null) {
                    shared.save(tenantId, local);
                    return;
                }
                // Not in this JVM's ring (another replica took the call): attach to the shared copy.
                Optional<LlmRequestSnapshot> held = shared.find(tenantId, sessionId, snapshotId);
                if (held.isPresent() && held.get().providerPromptTokens() == null) {
                    shared.save(tenantId, held.get().withProviderPromptTokens(tokens));
                }
            } catch (RuntimeException e) {
                BACKEND_DOWN_LOG.warnf("LLM request snapshot store could not update the shared copy: {}", e.toString(), e);
            }
        });
    }

    private void shareAsync(String tenantId, LlmRequestSnapshot snapshot) {
        LlmRequestSnapshotBackend shared = backend;
        ThreadPoolExecutor executor = writer;
        if (shared == null || executor == null) {
            return;
        }
        executor.execute(() -> {
            try {
                shared.save(tenantId, snapshot);
            } catch (RuntimeException e) {
                BACKEND_DOWN_LOG.warnf("LLM request snapshot store could not write to the shared store: {}", e.toString(), e);
            }
        });
    }

    /** Oldest first. Optionally narrowed to one agent and a time window (a workflow node's span). */
    public List<LlmRequestSnapshot> list(
        String tenantId, String sessionId, String agentId, Instant from, Instant to
    ) {
        Instant cutoff = Instant.now().minus(ttl);
        Map<String, LlmRequestSnapshot> merged = new LinkedHashMap<>();
        synchronized (this) {
            Deque<LlmRequestSnapshot> ring = bySession.get(key(tenantId, sessionId));
            if (ring != null) {
                ring.removeIf(s -> s.at().isBefore(cutoff));
                for (LlmRequestSnapshot s : ring) {
                    merged.put(s.id(), s);
                }
            }
        }
        LlmRequestSnapshotBackend shared = backend;
        if (shared != null && sessionId != null) {
            try {
                for (LlmRequestSnapshot s : shared.load(tenantId, sessionId)) {
                    if (!s.at().isBefore(cutoff)) {
                        merged.merge(s.id(), s, LlmRequestSnapshotStore::moreComplete);
                    }
                }
            } catch (RuntimeException e) {
                BACKEND_DOWN_LOG.warnf("LLM request snapshot store could not read the shared store: {}", e.toString(), e);
            }
        }
        List<LlmRequestSnapshot> out = new ArrayList<>(merged.size());
        for (LlmRequestSnapshot s : merged.values()) {
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
        out.sort(Comparator.comparing(LlmRequestSnapshot::at));
        return out;
    }

    /** Of two copies of the same call: the one that already has the provider's token count, else the first. */
    private static LlmRequestSnapshot moreComplete(LlmRequestSnapshot first, LlmRequestSnapshot second) {
        return first.providerPromptTokens() == null && second.providerPromptTokens() != null ? second : first;
    }

    /** Test seam: waits until every write queued before this call has been attempted. */
    boolean awaitWrites(Duration timeout) throws InterruptedException {
        ThreadPoolExecutor executor = writer;
        if (executor == null) {
            return true;
        }
        try {
            executor.submit(() -> { }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return false;
        }
    }

    /** Test seam: forgets this JVM's copies (a restart). */
    synchronized void clearLocal() {
        bySession.clear();
    }
}
