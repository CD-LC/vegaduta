package com.agenticai.core.settings;

import com.agenticai.core.security.TenantContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/** The {@link SettingsResolver}: platform, tenant and agent layers over {@link SettingsStore} and {@link SettingBackend}s. */
public class DefaultSettingsResolver implements SettingsResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultSettingsResolver.class);

    private record Cached(Map<String, Object> values, long expiresAtMillis) {}

    private final SettingsRegistry registry;
    private final SettingsStore store;
    private final List<SettingBackend> backends;
    private final SettingsProperties properties;
    private final Environment environment;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    public DefaultSettingsResolver(
            SettingsRegistry registry,
            SettingsStore store,
            List<SettingBackend> backends,
            SettingsProperties properties,
            Environment environment) {
        this(registry, store, backends, properties, environment, System::currentTimeMillis);
    }

    DefaultSettingsResolver(
            SettingsRegistry registry,
            SettingsStore store,
            List<SettingBackend> backends,
            SettingsProperties properties,
            Environment environment,
            java.util.function.LongSupplier clock) {
        this.registry = registry;
        this.store = store;
        this.backends = backends == null ? List.of() : List.copyOf(backends);
        this.properties = properties;
        this.environment = environment;
        this.clock = clock;
    }

    @Override
    public SettingsRegistry registry() {
        return registry;
    }

    @Override
    public SettingValue get(String key, SettingContext ctx) {
        SettingDefinition def = registry.find(key).orElseThrow(() -> new IllegalArgumentException("Unknown setting: " + key));
        Layers layers = layers(ctx == null ? SettingContext.tenant() : ctx, true, key);
        return resolve(def, layers);
    }

    @Override
    public Map<String, SettingValue> resolveAll(SettingContext ctx) {
        Layers layers = layers(ctx == null ? SettingContext.tenant() : ctx, true, null);
        Map<String, SettingValue> out = new LinkedHashMap<>();
        for (SettingDefinition def : registry.all()) {
            out.put(def.key(), resolve(def, layers));
        }
        return out;
    }

    /** The value a reset of the override at {@code scope} would give: tenant gives the platform, agent the tenant layer. */
    SettingValue resolveBelow(SettingDefinition def, SettingScope scope, SettingContext ctx) {
        if (scope == SettingScope.AGENT) {
            return resolve(def, layers(ctx, false, def.key()));
        }
        return resolve(def, new Layers(Map.of(), Map.of(), false));
    }

    // ------------------------------------------------------------------------------------------ resolution

    private record Layers(Map<String, Object> tenant, Map<String, Object> agent, boolean hasTenant) {}

    /** {@code onlyKey}: the one key being resolved (null = all), so a backend that does not own it is not read. */
    private Layers layers(SettingContext ctx, boolean withAgent, String onlyKey) {
        String tenant = ctx.effectiveTenant();
        if (tenant == null) {
            return new Layers(Map.of(), Map.of(), false);
        }
        Map<String, Object> t = new HashMap<>(storeTenant(tenant));
        for (SettingBackend b : backends) {
            if (onlyKey != null && !b.keys().contains(onlyKey)) {
                continue;
            }
            try {
                t.putAll(b.tenantValues());
            } catch (RuntimeException e) {
                log.warn("Could not read the tenant's settings from {}; using the platform values: {}", b.getClass().getSimpleName(), e.toString());
            }
        }
        Map<String, Object> a = Map.of();
        if (withAgent && ctx.agentId() != null) {
            a = new HashMap<>(storeAgent(tenant, ctx.agentId()));
            for (SettingBackend b : backends) {
                if (onlyKey != null && !b.keys().contains(onlyKey)) {
                    continue;
                }
                try {
                    a.putAll(b.agentValues(ctx.agentId()));
                } catch (RuntimeException e) {
                    log.warn("Could not read agent {} settings from {}; using the inherited values: {}", ctx.agentId(), b.getClass().getSimpleName(), e.toString());
                }
            }
        }
        return new Layers(t, a, true);
    }

    private SettingValue resolve(SettingDefinition def, Layers layers) {
        Object platform = def.platformValue();
        SettingSource platformSource = platformSource(def);
        if (properties.isLocked(def.key())) {
            return new SettingValue(platform, platformSource, true, properties.lockedReason(def.key()));
        }
        Object value = platform;
        SettingSource source = platformSource;
        Object belowValue = null;
        SettingSource belowSource = null;
        if (layers.hasTenant && def.allows(SettingScope.TENANT)) {
            Object stored = def.coerceStored(layers.tenant.get(def.key()));
            if (stored != null && def.isAtLeastAsStrict(stored, value)) {
                belowValue = value;
                belowSource = source;
                value = stored;
                source = SettingSource.TENANT;
            } else if (stored != null) {
                ignoreWeaker(def, stored, value, "workspace");
            }
        }
        if (layers.hasTenant && def.allows(SettingScope.AGENT)) {
            Object stored = def.coerceStored(layers.agent.get(def.key()));
            if (stored != null && def.isAtLeastAsStrict(stored, value)) {
                belowValue = value;
                belowSource = source;
                value = stored;
                source = SettingSource.AGENT;
            } else if (stored != null) {
                ignoreWeaker(def, stored, value, "agent");
            }
        }
        SettingValue.Inherited inherited = belowSource == null ? null : new SettingValue.Inherited(belowValue, belowSource);
        return new SettingValue(value, source, false, null, inherited);
    }

    /**
     * A stored security value that is weaker than the value it would override (the write path refuses these, so one can only
     * get here through a row written around the API, or because the platform value was tightened after the override was
     * stored): the stricter value applies and the operator is told, once per key and value.
     */
    private void ignoreWeaker(SettingDefinition def, Object stored, Object inherited, String layer) {
        if (warnedWeaker.size() >= 512) {
            warnedWeaker.clear();
        }
        if (warnedWeaker.add(def.key() + "|" + layer + "|" + stored + "|" + inherited)) {
            log.warn("Ignoring the {} value {} of security setting {}: it is weaker than {} which applies. Only a stricter value "
                    + "can override a security setting.", layer, stored, def.key(), inherited);
        }
    }

    private final java.util.Set<String> warnedWeaker = ConcurrentHashMap.newKeySet();

    private SettingSource platformSource(SettingDefinition def) {
        if (def.platformProperty() != null && environment != null && environment.containsProperty(def.platformProperty())) {
            return SettingSource.PLATFORM;
        }
        return SettingSource.DEFAULT;
    }

    // ------------------------------------------------------------------------------------------ cache

    private Map<String, Object> storeTenant(String tenant) {
        if (store == null) {
            return Map.of();
        }
        return cached("t:" + tenant, () -> store.tenantValues(), "the tenant's");
    }

    private Map<String, Object> storeAgent(String tenant, UUID agentId) {
        if (store == null) {
            return Map.of();
        }
        return cached("a:" + tenant + ":" + agentId, () -> store.agentValues(agentId.toString()), "agent " + agentId + "'s");
    }

    private Map<String, Object> cached(String key, java.util.function.Supplier<Map<String, Object>> load, String what) {
        long now = clock.getAsLong();
        Cached c = cache.get(key);
        if (c != null && c.expiresAtMillis() > now) {
            return c.values();
        }
        try {
            Map<String, Object> loaded = Map.copyOf(load.get());
            long ttl = properties.getCacheTtl().toMillis();
            if (ttl > 0) {
                if (cache.size() >= properties.getCacheMaxEntries()) {
                    cache.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
                    if (cache.size() >= properties.getCacheMaxEntries()) {
                        cache.clear();
                    }
                }
                cache.put(key, new Cached(loaded, now + ttl));
            }
            return loaded;
        } catch (RuntimeException e) {
            // A read failure must not stop a turn: the platform value is the safe baseline.
            log.warn("Could not read {} stored settings; using the platform values: {}", what, e.toString());
            return Map.of();
        }
    }

    @Override
    public void evictCurrentTenant() {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenant == null) {
            return;
        }
        String t = "t:" + tenant;
        String a = "a:" + tenant + ":";
        cache.keySet().removeIf(k -> k.equals(t) || k.startsWith(a));
    }
}
