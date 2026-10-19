package com.agenticai.core.security;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Read-through cache for the small "is this configured" answers a chat turn asks over and over: is
 * any MCP server enabled, is the ElevenLabs credential active, does this agent hold an active
 * payment mandate. Each answer used to be its own database transaction (a connection checkout plus
 * two {@code SET search_path} round trips), repeated on every turn although it changes a few times
 * a year.
 *
 * <p>Two scopes. {@link #tenant} keys by the current tenant first, so an answer is never shared
 * across tenants; with no tenant context it bypasses the cache. {@link #platform} is for rows that
 * are the same for everyone. Both are per pod, bounded, and expire after {@code
 * app.chat.hotpath.config-lookup-ttl}; a write on this pod evicts at once ({@link
 * HotpathCaches#evictTenantConfig}, {@link HotpathCaches#evictPlatformConfig}), another pod sees it
 * within the TTL. A TTL of zero, which is also the state before {@link #configure} runs (every unit
 * test), makes both a pass-through, so behaviour is exactly the old one.
 *
 * <p>Only cache small immutable answers (a boolean, an immutable list, an encrypted row). Never an
 * answer that decides access: authorization reads the row.
 */
public final class HotpathLookups {

    private static final int MAX_ENTRIES = 50_000;

    private static volatile TtlCache<Object> tenantCache =
            new TtlCache<>(HotpathCaches.TENANT_CONFIG, Duration.ZERO, MAX_ENTRIES);
    private static volatile TtlCache<Object> platformCache =
            new TtlCache<>(HotpathCaches.PLATFORM_CONFIG, Duration.ZERO, MAX_ENTRIES);

    private HotpathLookups() {}

    /** Called once at startup with the configured TTL; zero or negative turns the caches off. */
    public static void configure(Duration ttl) {
        tenantCache = new TtlCache<>(HotpathCaches.TENANT_CONFIG, ttl, MAX_ENTRIES);
        platformCache = new TtlCache<>(HotpathCaches.PLATFORM_CONFIG, ttl, MAX_ENTRIES);
    }

    public static <T> T tenant(String lookup, Supplier<T> loader) {
        return tenant(lookup, "", loader);
    }

    /** {@code arg} distinguishes one lookup per subject, for example an agent id. */
    @SuppressWarnings("unchecked")
    public static <T> T tenant(String lookup, String arg, Supplier<T> loader) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TtlCache<Object> cache = tenantCache;
        if (tenantId == null || !cache.isEnabled()) {
            return loader.get();
        }
        return (T) cache.get(TtlCache.key(tenantId, lookup, arg), loader::get);
    }

    @SuppressWarnings("unchecked")
    public static <T> T platform(String lookup, String arg, Supplier<T> loader) {
        TtlCache<Object> cache = platformCache;
        if (!cache.isEnabled()) {
            return loader.get();
        }
        return (T) cache.get(TtlCache.key(lookup, arg), loader::get);
    }
}
