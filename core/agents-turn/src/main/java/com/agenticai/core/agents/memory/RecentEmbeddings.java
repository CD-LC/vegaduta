package com.agenticai.core.agents.memory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * A tiny, short-lived cache of the last few query embeddings, keyed by tenant and text. A chat turn embeds the user
 * message once for the legacy {@code agent_memory} recall ({@link QueryEmbedding}) and the unified-memory store embeds
 * the same message again through {@code MemoryEmbedder}; both go through here so the sidecar is asked once (ADR-0028
 * WP4c). The key includes the tenant, nothing is shared across tenants, and without a tenant nothing is cached.
 *
 * <p>Bounded in count and age, so it holds a handful of vectors for seconds, never a history of what users said. The
 * (blocking) embedder never runs under the lock, which only guards the map: a virtual thread must not park on a
 * monitor while waiting on the network (see {@link QueryEmbedding}). Two simultaneous misses on the same key may both
 * embed; the sequential recalls of one turn cannot.
 */
final class RecentEmbeddings {

    static final int MAX_ENTRIES = 8;
    static final Duration TTL = Duration.ofSeconds(20);

    private record Slot(float[] vector, long atNanos) {}

    private final ReentrantLock lock = new ReentrantLock();
    private final LongSupplier nanos;
    private final Map<String, Slot> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Slot> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    RecentEmbeddings() {
        this(System::nanoTime);
    }

    RecentEmbeddings(LongSupplier nanos) {
        this.nanos = nanos;
    }

    float[] get(String tenantId, String text, Function<String, float[]> embedder) {
        if (tenantId == null || tenantId.isBlank() || text == null) {
            return embedder.apply(text);
        }
        String key = tenantId + '\u0000' + text;
        lock.lock();
        try {
            Slot hit = entries.get(key);
            if (hit != null) {
                if (nanos.getAsLong() - hit.atNanos() <= TTL.toNanos()) {
                    return hit.vector();
                }
                entries.remove(key);
            }
        } finally {
            lock.unlock();
        }
        float[] vector = embedder.apply(text);
        if (vector != null && vector.length > 0) {
            lock.lock();
            try {
                entries.put(key, new Slot(vector, nanos.getAsLong()));
            } finally {
                lock.unlock();
            }
        }
        return vector;
    }
}
