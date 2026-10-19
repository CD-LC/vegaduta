package com.agenticai.core.agents.turn;

import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-tenant prompt-directive overrides for the chat hot path. A turn calls {@link #forToday} once;
 * the answer comes from an in-process cache (TTL {@value #TTL_SECONDS}s, evicted explicitly on
 * every write), so no turn waits on the database unless the cache is cold. The tenant is the one on
 * the calling thread ({@link TenantContext}); with none, or with no {@link Store} bean, or if the
 * load fails, the answer is {@link ResolvedDirectives#DEFAULTS}, i.e. today's built-in prompt.
 */
@Component
public class PromptDirectiveSettings {

    static final long TTL_SECONDS = 30;
    private static final long FAILURE_TTL_SECONDS = 5;
    private static final int MAX_TENANTS = 10_000;
    private static final Logger log = LoggerFactory.getLogger(PromptDirectiveSettings.class);

    /** Where a tenant's stored settings come from; implemented next to the table. */
    public interface Store {
        /** Every stored setting of the current tenant (directive key to setting). */
        Map<String, ResolvedDirectives.Setting> loadAllForCurrentTenant();
    }

    private record Cached(Map<String, ResolvedDirectives.Setting> settings, long expiresAtNanos) {}

    private final ObjectProvider<Store> store;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public PromptDirectiveSettings(ObjectProvider<Store> store) {
        this.store = store;
        this.nanoClock = System::nanoTime;
    }

    private PromptDirectiveSettings(ObjectProvider<Store> store, LongSupplier nanoClock) {
        this.store = store;
        this.nanoClock = nanoClock;
    }

    /** No store: every turn gets the built-in prompt. The default of hand-built fixtures. */
    static PromptDirectiveSettings defaultsOnly() {
        return new PromptDirectiveSettings(null, System::nanoTime);
    }

    /** Test seam: a store given directly and a controllable clock. */
    static PromptDirectiveSettings forTest(Store store, LongSupplier nanoClock) {
        return new PromptDirectiveSettings(
                new org.springframework.beans.factory.support.StaticListableBeanFactory(
                                Map.of("store", store))
                        .getBeanProvider(Store.class),
                nanoClock);
    }

    /** The current tenant's directives for a turn on {@code today} (UTC). Never throws. */
    public ResolvedDirectives forToday(LocalDate today) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return ResolvedDirectives.DEFAULTS;
        }
        long now = nanoClock.getAsLong();
        Cached cached = cache.get(tenantId);
        if (cached == null || now - cached.expiresAtNanos() >= 0) {
            cached = load(tenantId, now);
        }
        return cached.settings().isEmpty()
                ? ResolvedDirectives.DEFAULTS
                : new ResolvedDirectives(cached.settings(), today);
    }

    private Cached load(String tenantId, long now) {
        Store s = store == null ? null : store.getIfAvailable();
        Cached fresh;
        try {
            Map<String, ResolvedDirectives.Setting> loaded =
                    s == null ? Map.of() : Map.copyOf(s.loadAllForCurrentTenant());
            fresh = new Cached(loaded, now + Duration.ofSeconds(TTL_SECONDS).toNanos());
        } catch (RuntimeException e) {
            log.warn("Prompt directive settings not loaded for a tenant; using built-in defaults", e);
            fresh = new Cached(Map.of(), now + Duration.ofSeconds(FAILURE_TTL_SECONDS).toNanos());
        }
        if (cache.size() >= MAX_TENANTS) {
            cache.clear();
        }
        cache.put(tenantId, fresh);
        return fresh;
    }

    /** Drops the current tenant's cached settings; call after every write. */
    public void evictCurrentTenant() {
        TenantContext.current().ifPresent(c -> cache.remove(c.getTenantId()));
    }
}
