package com.agenticai.core.security;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Tiny bounded TTL cache for the chat hot path (per JVM, so per pod). Keys are Strings built by {@link
 * #key(String...)} with the tenant id ALWAYS first, so a value can never be shared across tenants and one
 * tenant's entries can be evicted by prefix from a write hook that has no access to the cache instance
 * (JPA entity listeners): every cache registers itself under a stable name and {@link
 * #evictPrefix(String, String...)} reaches all live instances of that name.
 *
 * <p>Deliberately dumb: a {@code ttl <= 0} cache is a pass-through (every call runs the loader, nothing is
 * stored - today's behaviour), loader results that are {@code null} are never stored, loader exceptions
 * propagate and are never cached, and when the bound is hit expired entries are purged and, failing that,
 * the map is cleared (a full flush is always safe for a cache). There is no cross-pod invalidation: a write
 * on pod A is visible on pod B after at most one TTL, which is the staleness bound callers must document.
 */
public final class TtlCache<V> {

    private static final char SEP = '\u0001';

    private static final Map<String, Set<TtlCache<?>>> REGISTRY = new ConcurrentHashMap<>();

    private record Entry<V>(V value, long expiresAtNanos) {}

    private final String name;
    private final long ttlNanos;
    private final int maxEntries;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Entry<V>> map = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public TtlCache(String name, Duration ttl, int maxEntries) {
        this(name, ttl, maxEntries, System::nanoTime);
    }

    TtlCache(String name, Duration ttl, int maxEntries, LongSupplier nanoClock) {
        this.name = name;
        this.ttlNanos = ttl == null ? 0L : Math.max(0L, ttl.toNanos());
        this.maxEntries = Math.max(1, maxEntries);
        this.nanoClock = nanoClock;
        REGISTRY.computeIfAbsent(name, n -> Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()))).add(this);
    }

    /** Tenant-first composite key; every part is followed by a separator so prefixes are unambiguous. */
    public static String key(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(p).append(SEP);
        }
        return sb.toString();
    }

    public boolean isEnabled() {
        return ttlNanos > 0;
    }

    public String name() {
        return name;
    }

    /** Cached value if fresh, else {@code loader.get()} (stored when the cache is enabled and the value non-null). */
    public V get(String key, Supplier<V> loader) {
        if (ttlNanos <= 0) {
            return loader.get();
        }
        long now = nanoClock.getAsLong();
        Entry<V> e = map.get(key);
        if (e != null && now - e.expiresAtNanos < 0) {
            hits.increment();
            return e.value;
        }
        misses.increment();
        V loaded = loader.get();
        if (loaded != null) {
            if (map.size() >= maxEntries) {
                map.values().removeIf(x -> now - x.expiresAtNanos >= 0);
                if (map.size() >= maxEntries) {
                    map.clear();
                }
            }
            map.put(key, new Entry<>(loaded, now + ttlNanos));
        }
        return loaded;
    }

    public void invalidate(String key) {
        map.remove(key);
    }

    public void invalidatePrefix(String prefix) {
        map.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void clear() {
        map.clear();
    }

    public int size() {
        return map.size();
    }

    public long hits() {
        return hits.sum();
    }

    public long misses() {
        return misses.sum();
    }

    /** Evicts {@code key(parts)}-prefixed entries from every live cache registered under {@code name}. */
    public static void evictPrefix(String name, String... parts) {
        Set<TtlCache<?>> caches = REGISTRY.get(name);
        if (caches == null) {
            return;
        }
        String prefix = key(parts);
        synchronized (caches) {
            for (TtlCache<?> c : caches) {
                c.invalidatePrefix(prefix);
            }
        }
    }

    /** Flushes every live cache registered under {@code name} (write hook that cannot tell which tenant). */
    public static void evictAll(String name) {
        Set<TtlCache<?>> caches = REGISTRY.get(name);
        if (caches == null) {
            return;
        }
        synchronized (caches) {
            for (TtlCache<?> c : caches) {
                c.clear();
            }
        }
    }
}
