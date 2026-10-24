package com.agenticai.core.prompts;

import com.agenticai.core.security.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link PromptResolver}: classpath defaults under scoped overrides, with the overrides cached
 * in process.
 *
 * <p><b>Cache and replicas.</b> The platform's rows are cached once, a tenant's rows (TENANT and
 * AGENT scope together) once per tenant, each for {@code app.prompts.cache.ttl} (default 30 s). A
 * write evicts the writing replica at once ({@link PromptCacheControl}); every other replica serves
 * its cached copy until the TTL runs out, so a change reaches all replicas within one TTL. There is
 * no cross-replica push and no per-lookup version query: the hot path never waits on the database
 * unless the cache is cold. A failed load caches "no overrides" for {@code failure-ttl} (default 5
 * s) and logs a warning, so a database outage serves the defaults instead of failing turns.
 */
public final class DefaultPromptResolver implements PromptResolver, PromptCacheControl {

    private static final Logger log = LoggerFactory.getLogger(DefaultPromptResolver.class);

    private record Rows(Map<String, PromptOverride> byKey, long expiresAtNanos, boolean failed) {}

    private record Outcome(ResolvedPrompt hit, boolean degraded) {}

    private final PromptCatalog catalog;
    private final PromptOverrideStore store;
    private final PromptProperties properties;
    private final LongSupplier nanoClock;
    private final AtomicReference<Rows> platform = new AtomicReference<>();
    private final ConcurrentHashMap<String, Rows> tenants = new ConcurrentHashMap<>();
    private final Set<String> unknownLogged = ConcurrentHashMap.newKeySet();
    private final Map<String, PromptDefaultAlias> aliases;

    public DefaultPromptResolver(PromptCatalog catalog, PromptOverrideStore store, PromptProperties properties) {
        this(catalog, store, properties, System::nanoTime);
    }

    /**
     * As above, with the deprecated properties that seed the default of a key (see {@link
     * PromptDefaultAlias}); at most one per key, the first wins.
     */
    public DefaultPromptResolver(
            PromptCatalog catalog,
            PromptOverrideStore store,
            PromptProperties properties,
            List<? extends PromptDefaultAlias> aliases) {
        this(catalog, store, properties, System::nanoTime, aliases);
    }

    /** Test seam: a controllable clock. */
    public DefaultPromptResolver(
            PromptCatalog catalog, PromptOverrideStore store, PromptProperties properties, LongSupplier nanoClock) {
        this(catalog, store, properties, nanoClock, List.of());
    }

    /** Test seam: a controllable clock and the property aliases. */
    public DefaultPromptResolver(
            PromptCatalog catalog,
            PromptOverrideStore store,
            PromptProperties properties,
            LongSupplier nanoClock,
            List<? extends PromptDefaultAlias> aliases) {
        this.catalog = catalog;
        this.store = store;
        this.properties = properties;
        this.nanoClock = nanoClock;
        Map<String, PromptDefaultAlias> byKey = new HashMap<>();
        if (aliases != null) {
            for (PromptDefaultAlias alias : aliases) {
                if (byKey.putIfAbsent(alias.key(), alias) != null) {
                    log.warn("Prompt key '{}' has more than one property alias; the first one is used", alias.key());
                }
            }
        }
        this.aliases = Map.copyOf(byKey);
    }

    @Override
    public PromptCatalog catalog() {
        return catalog;
    }

    @Override
    public ResolvedPrompt resolve(String key, PromptContext context) {
        PromptDefinition def = catalog.find(key).orElse(null);
        if (def == null) {
            if (unknownLogged.add(String.valueOf(key))) {
                log.error("Prompt key '{}' is not registered; nothing will be sent for it", key);
            }
            return ResolvedPrompt.unknown(key);
        }
        PromptContext ctx = context == null ? PromptContext.none() : context;
        String locale = ctx.locale() == null || ctx.locale().isBlank() ? catalog.defaultLocale() : ctx.locale();
        PromptSource fallback = PromptSource.DEFAULT;
        if (store != null && !def.retired()) {
            try {
                Outcome outcome = fromOverrides(def, ctx, locale);
                if (outcome.hit() != null) {
                    return outcome.hit();
                }
                if (outcome.degraded()) {
                    fallback = PromptSource.FALLBACK;
                }
            } catch (RuntimeException e) {
                log.warn("Prompt overrides for '{}' could not be read; serving the default text: {}", key, e.toString());
                fallback = PromptSource.FALLBACK;
            }
        }
        String text = catalog.defaultText(key, locale).orElse("");
        String usedLocale = catalog.locales(key).contains(locale) ? locale : catalog.defaultLocale();
        String seeded = def.retired() ? null : aliasText(key, locale);
        return build(def, fallback, null, 0, usedLocale, true, seeded != null ? seeded : text, ctx);
    }

    /**
     * The text a deprecated property sets as the default of {@code key} in {@code locale}, or null. It sits
     * below every stored override (it IS the platform default) and replaces only the shipped file.
     */
    private String aliasText(String key, String locale) {
        PromptDefaultAlias alias = aliases.get(key);
        if (alias == null) {
            return null;
        }
        try {
            String text = alias.text(locale);
            return text == null || text.isBlank() ? null : text;
        } catch (RuntimeException e) {
            log.warn("The property alias of prompt '{}' could not be read; serving the shipped text: {}", key, e.toString());
            return null;
        }
    }

    @Override
    public Map<String, ResolvedPrompt> resolveAll(PromptContext context) {
        Map<String, ResolvedPrompt> out = new TreeMap<>();
        for (PromptDefinition def : catalog.all()) {
            out.put(def.key(), resolve(def.key(), context));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------

    private Outcome fromOverrides(PromptDefinition def, PromptContext ctx, String locale) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        boolean tenantRows = tenantId != null;
        if (ctx.tenantId() != null && !ctx.tenantId().equals(tenantId)) {
            log.warn(
                    "Prompt context is for tenant {} but the calling thread is {}; ignoring tenant and agent overrides",
                    ctx.tenantId(),
                    tenantId);
            tenantRows = false;
        }
        Rows tenantSet = tenantRows ? tenantRows(tenantId) : null;
        Rows platformSet = platformRows();
        boolean degraded = platformSet.failed() || (tenantSet != null && tenantSet.failed());
        Map<String, PromptOverride> tenantMap = tenantSet == null ? Map.of() : tenantSet.byKey();
        Map<String, PromptOverride> platformMap = platformSet.byKey();
        String defaultLocale = catalog.defaultLocale();
        for (PromptScope scope : PromptScope.values()) {
            Map<String, PromptOverride> map = scope == PromptScope.PLATFORM ? platformMap : tenantMap;
            String scopeId = scope == PromptScope.AGENT ? agentId(ctx.agentId()) : "";
            if (scope == PromptScope.AGENT && scopeId == null) {
                continue;
            }
            PromptOverride row = map.get(rowKey(scope, scopeId, def.key(), locale));
            if (row == null && !locale.equals(defaultLocale)) {
                row = map.get(rowKey(scope, scopeId, def.key(), defaultLocale));
            }
            if (row == null) {
                continue;
            }
            if (!row.enabled()) {
                if (def.disableable()) {
                    return new Outcome(
                            new ResolvedPrompt(
                                    def.key(), null, PromptSource.of(scope), scope, row.version(), row.locale(), false, true, "", 0),
                            degraded);
                }
                log.warn("Ignoring a switched-off override of '{}' ({}): the prompt cannot be disabled", def.key(), scope);
                continue;
            }
            if (row.text() == null || row.text().isBlank()) {
                continue;
            }
            return new Outcome(
                    build(def, PromptSource.of(scope), scope, row.version(), row.locale(), true, row.text(), ctx), degraded);
        }
        return new Outcome(null, degraded);
    }

    private ResolvedPrompt build(
            PromptDefinition def,
            PromptSource source,
            PromptScope scope,
            int version,
            String locale,
            boolean enabled,
            String rawText,
            PromptContext ctx) {
        String rendered = PromptTemplates.render(rawText, def.placeholderNames(), ctx.variables());
        int tokens = rendered == null || rendered.isEmpty() ? 0 : (int) Math.ceil(rendered.length() / properties.getCharsPerToken());
        return new ResolvedPrompt(def.key(), rendered, source, scope, version, locale, enabled, true, ResolvedPrompt.hashOf(rawText), tokens);
    }

    private static String agentId(UUID agentId) {
        return agentId == null ? null : agentId.toString();
    }

    static String rowKey(PromptScope scope, String scopeId, String key, String locale) {
        return scope.dbValue() + '|' + scopeId + '|' + key + '|' + locale;
    }

    private Rows platformRows() {
        long now = nanoClock.getAsLong();
        Rows cached = platform.get();
        if (cached == null || now - cached.expiresAtNanos() >= 0) {
            cached = load(now, true);
            platform.set(cached);
        }
        return cached;
    }

    private Rows tenantRows(String tenantId) {
        long now = nanoClock.getAsLong();
        Rows cached = tenants.get(tenantId);
        if (cached == null || now - cached.expiresAtNanos() >= 0) {
            cached = load(now, false);
            if (tenants.size() >= properties.getCache().getMaxTenants()) {
                tenants.clear();
            }
            tenants.put(tenantId, cached);
        }
        return cached;
    }

    private Rows load(long now, boolean platformRows) {
        try {
            Map<String, PromptOverride> map = new HashMap<>();
            for (PromptOverride row : platformRows ? store.loadPlatform() : store.loadCurrentTenant()) {
                map.put(rowKey(row.scope(), row.scopeId() == null ? "" : row.scopeId(), row.key(), row.locale()), row);
            }
            return new Rows(Map.copyOf(map), now + properties.getCache().getTtl().toNanos(), false);
        } catch (RuntimeException e) {
            log.warn(
                    "{} prompt overrides could not be loaded; serving the default texts for {}: {}",
                    platformRows ? "Platform" : "Tenant",
                    properties.getCache().getFailureTtl(),
                    e.toString());
            return new Rows(Map.of(), now + properties.getCache().getFailureTtl().toNanos(), true);
        }
    }

    @Override
    public void evictCurrentTenant() {
        TenantContext.current().ifPresent(c -> tenants.remove(c.getTenantId()));
    }

    @Override
    public void evictPlatform() {
        platform.set(null);
    }

    @Override
    public void evictAll() {
        platform.set(null);
        tenants.clear();
    }
}
