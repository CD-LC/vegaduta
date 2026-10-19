package com.agenticai.core.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.google.genai.cache.CachedContentRequest;
import org.springframework.ai.google.genai.cache.GoogleGenAiCachedContent;
import org.springframework.ai.google.genai.cache.GoogleGenAiCachedContentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Shared, in-memory reuse-with-TTL tracker for Gemini/Vertex {@code
 * CachedContent} resources, sitting on top of Spring AI 1.1's real {@link
 * GoogleGenAiCachedContentService} (the actual Caches API client - this is
 * NOT a second caching mechanism, it is the "is there already a live one for
 * this key" bookkeeping that avoids re-creating a cache resource per call).
 *
 * <p><b>Not wired today (2026-09-28).</b> {@link GeminiProviderCachingStrategy}
 * and {@link VertexProviderCachingStrategy} are documented no-ops - see the
 * Gemini strategy's class javadoc for why explicit caching cannot be attached
 * safely at the ChatClient build-time hook - so nothing calls {@link
 * #getOrCreate} and the map stays empty. This class was nevertheless fixed so
 * that whoever wires the per-request path starts from correct primitives:
 * <ul>
 *   <li><b>Content-aware key.</b> {@link #cacheKey} hashes the credential
 *       fingerprint, model, system prompt AND tool schemas. The old key was
 *       {@code hash(credential):model} only, so two agents with different
 *       system prompts on one credential+model would have reused each other's
 *       cached prefix - a cross-agent system-prompt bleed.</li>
 *   <li><b>Eviction.</b> Expired entries are purged on every write, and the
 *       map is capped at {@link #DEFAULT_MAX_ENTRIES} (earliest-expiring
 *       entries evicted first). The old map only ever grew.</li>
 * </ul>
 *
 * <p><b>Two-replica analysis (2026-09-23): cost duplication only, NOT a
 * correctness issue.</b> The map caches a REMOTE resource name; a replica
 * lacking an entry creates its own copy (same content, own name, own TTL).
 * Nothing here is a lock, counter or source of truth. If the duplicated
 * creation cost ever matters, the fix is a Redis {@code name + expireTime}
 * entry per key (the {@code ToolCircuitBreakerService} shape).
 */
@Component
public class GoogleGenAiCacheManager {

    private static final Logger log = LoggerFactory.getLogger(GoogleGenAiCacheManager.class);

    static final int DEFAULT_MAX_ENTRIES = 500;

    /** Treat a cache as dead this long before its real expiry so an in-flight call never races server-side expiry. */
    private static final Duration EXPIRY_SAFETY_MARGIN = Duration.ofSeconds(30);

    private final Map<String, CacheEntry> entriesByKey = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxEntries;

    private record CacheEntry(String name, Instant expiresAt) {
        boolean isLiveAt(Instant now) {
            return now.isBefore(expiresAt.minus(EXPIRY_SAFETY_MARGIN));
        }
    }

    @Autowired
    public GoogleGenAiCacheManager() {
        this(Clock.systemUTC(), DEFAULT_MAX_ENTRIES);
    }

    /** Test seam. */
    GoogleGenAiCacheManager(Clock clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * The cache key for one cacheable prefix: SHA-256 over the credential
     * fingerprint, model, system prompt and every tool schema, each
     * length-prefixed so no two different inputs can concatenate to the same
     * byte stream. The raw credential never appears in the key (it is hashed
     * like every other component). Any change to the system prompt or a tool
     * schema yields a new key - which is what makes cache reuse safe across
     * agents that share a credential and model.
     */
    public static String cacheKey(String credential, String model, String systemPrompt, List<String> toolSchemas) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateField(digest, "credential", credential);
            updateField(digest, "model", model);
            updateField(digest, "system", systemPrompt);
            List<String> tools = toolSchemas == null ? List.of() : toolSchemas;
            updateField(digest, "toolCount", Integer.toString(tools.size()));
            for (String tool : tools) {
                updateField(digest, "tool", tool);
            }
            return "gcc:" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm; unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void updateField(MessageDigest digest, String label, String value) {
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(labelBytes);
        digest.update((byte) 0);
        // null and "" must not collide either.
        digest.update((byte) (value == null ? 0 : 1));
        digest.update(Integer.toString(valueBytes.length).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(valueBytes);
    }

    /**
     * The resource name to reference on this call, or {@code null} if
     * nothing is currently live for {@code key} (never created, expired, or
     * a prior creation failed). An expired entry found here is removed.
     * Never throws.
     */
    public String liveCacheName(String key) {
        CacheEntry entry = entriesByKey.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isLiveAt(clock.instant())) {
            return entry.name();
        }
        entriesByKey.remove(key, entry);
        return null;
    }

    /**
     * Reuses the live cache for {@code key} if one exists; otherwise calls
     * {@code service.create(request)} and tracks its expiry ({@code
     * getExpireTime()} when the API returned one, else {@code now + ttl}).
     *
     * <p><b>Fails open.</b> Any exception from the Caches API is logged and
     * swallowed and {@code null} returned, so a chat request always proceeds
     * (uncached) instead of breaking on a cost optimization.
     */
    public String getOrCreate(GoogleGenAiCachedContentService service, String key, CachedContentRequest request, Duration ttl) {
        String live = liveCacheName(key);
        if (live != null) {
            return live;
        }
        try {
            GoogleGenAiCachedContent created = service.create(request);
            Instant expiresAt = created.getExpireTime() != null ? created.getExpireTime() : clock.instant().plus(ttl);
            evictExpiredAndOverflow();
            entriesByKey.put(key, new CacheEntry(created.getName(), expiresAt));
            return created.getName();
        } catch (Exception e) {
            log.warn("Gemini/Vertex cached-content creation failed for key '{}' - proceeding uncached", key, e);
            entriesByKey.remove(key);
            return null;
        }
    }

    /** Number of tracked entries (live or not yet purged) - for tests and diagnostics. */
    int size() {
        return entriesByKey.size();
    }

    /**
     * Drops every expired entry, then - if still at capacity - the entries
     * closest to expiry until one slot is free. O(n) over at most {@link
     * #maxEntries} entries, only on the (rare) creation path.
     */
    private void evictExpiredAndOverflow() {
        Instant now = clock.instant();
        entriesByKey.entrySet().removeIf(e -> !e.getValue().isLiveAt(now));
        int excess = entriesByKey.size() - maxEntries + 1;
        if (excess <= 0) {
            return;
        }
        entriesByKey.entrySet().stream()
            .sorted(Comparator.comparing((Map.Entry<String, CacheEntry> e) -> e.getValue().expiresAt()))
            .limit(excess)
            .map(Map.Entry::getKey)
            .toList()
            .forEach(entriesByKey::remove);
    }
}
