package com.agenticai.core.settings;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * THE lookup every use site calls. Resolution: agent override, then tenant override, then the platform value (the
 * deployment's property object, the same one the runtime reads), then the code default; a key an operator pinned
 * ({@code app.settings.locked}) is always the platform value; a SECURITY key takes the stricter of the layers, so no
 * override can weaken it below the platform value.
 *
 * <p>Never throws because of the store: on any store error the platform value is returned and a warning logged.
 * Overrides are cached per tenant for {@code app.settings.cache-ttl} and evicted on every write made through this
 * replica, so other replicas converge within the TTL. An unknown key is a programming error and throws
 * {@link IllegalArgumentException}.
 *
 * <p>Typical use: {@code settings.getInt("chat.finish.maxContinuations", SettingContext.forAgent(agentId))}; with no
 * agent, {@link SettingContext#tenant()}. The tenant is the calling thread's {@code TenantContext}.
 */
public interface SettingsResolver {

    /** The effective value of {@code key} with its source. */
    SettingValue get(String key, SettingContext ctx);

    /** Every catalog key resolved for the context (one store read per layer). */
    Map<String, SettingValue> resolveAll(SettingContext ctx);

    /** The catalog. */
    SettingsRegistry registry();

    /** Drops the cached overrides of the current tenant (called after a write). */
    default void evictCurrentTenant() {}

    // ------------------------------------------------------------------------------------------ typed accessors

    default int getInt(String key, SettingContext ctx) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number(key, ctx).longValue()));
    }

    default int getInt(String key, UUID agentId) {
        return getInt(key, SettingContext.forAgent(agentId));
    }

    default long getLong(String key, SettingContext ctx) {
        return number(key, ctx).longValue();
    }

    default double getDecimal(String key, SettingContext ctx) {
        return number(key, ctx).doubleValue();
    }

    default double getDecimal(String key, UUID agentId) {
        return getDecimal(key, SettingContext.forAgent(agentId));
    }

    default boolean getBool(String key, SettingContext ctx) {
        Object v = get(key, ctx).value();
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    default boolean getBool(String key, UUID agentId) {
        return getBool(key, SettingContext.forAgent(agentId));
    }

    default String getString(String key, SettingContext ctx) {
        return String.valueOf(get(key, ctx).value());
    }

    /** A duration setting as a {@link Duration}, using the entry's unit ({@code ms} or {@code s}). */
    default Duration getDuration(String key, SettingContext ctx) {
        SettingDefinition def = registry().require(key);
        long n = number(key, ctx).longValue();
        return def.unit() == SettingUnit.MS ? Duration.ofMillis(n) : Duration.ofSeconds(n);
    }

    default Duration getDuration(String key, UUID agentId) {
        return getDuration(key, SettingContext.forAgent(agentId));
    }

    /** An enum setting as a Java enum: the value is matched to a constant ignoring case, dashes and underscores. */
    default <E extends Enum<E>> E getEnum(String key, SettingContext ctx, Class<E> type) {
        String v = String.valueOf(get(key, ctx).value());
        String wanted = v.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (E e : type.getEnumConstants()) {
            if (e.name().toLowerCase(Locale.ROOT).replace("_", "").equals(wanted)) {
                return e;
            }
        }
        throw new IllegalStateException("Setting " + key + " = " + v + " is not a constant of " + type.getSimpleName());
    }

    private Number number(String key, SettingContext ctx) {
        Object v = get(key, ctx).value();
        if (v instanceof Number n) {
            return n;
        }
        throw new IllegalStateException("Setting " + key + " is not numeric: " + v);
    }

    // ------------------------------------------------------------------------------------------ test support

    /**
     * A resolver over the registry that answers the platform values only (no store, no overrides): for use sites
     * built with {@code new} in tests and embedded use.
     */
    static SettingsResolver platformOnly(SettingsRegistry registry) {
        return new DefaultSettingsResolver(registry, null, java.util.List.of(), new SettingsProperties(), null);
    }
}
