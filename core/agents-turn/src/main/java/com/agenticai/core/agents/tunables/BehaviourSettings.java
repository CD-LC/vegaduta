package com.agenticai.core.agents.tunables;

import com.agenticai.core.security.TenantScope;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * (Lives in its own leaf package, not next to the catalog, so the turn, memory and routing packages can use it without
 * forming a package cycle with the catalog that reads their property classes.)
 *
 * <p>What the turn path asks for a curated behaviour setting (docs/settings.md): the value for an agent of the tenant a
 * {@link TenantScope} carries, with the platform value as the answer whenever the catalog cannot give one.
 *
 * <p>Why this exists next to {@link SettingsResolver}: the resolver reads the tenant from the calling thread, and a
 * turn's later stages run on HTTP-client and scheduler threads that hold none. Every read here is made inside the turn's
 * scope, so an override is applied the same way on the streamed, the buffered, the delegated and the continuation path
 * and never silently dropped on a thread without a tenant. The resolver caches the overrides per tenant, so a read is a
 * map lookup after the first: call it once per decision (a turn, a pass), never per token.
 *
 * <p>The {@code platform} argument is what the use site would have used without the catalog (the same property object
 * the catalog entry reads). It answers when there is no resolver (hand-built fixtures and tests), and when a read fails
 * (a warning is logged): a settings problem never fails a turn. With a resolver and no override the resolver's answer is
 * that same value, which a test per key pins.
 */
public final class BehaviourSettings {

    private static final Logger log = LoggerFactory.getLogger(BehaviourSettings.class);

    private static final BehaviourSettings PLATFORM_ONLY = new BehaviourSettings(() -> null);

    private final Supplier<SettingsResolver> resolver;

    private BehaviourSettings(Supplier<SettingsResolver> resolver) {
        this.resolver = resolver;
    }

    /** Every answer is the platform value (no catalog): hand-built fixtures and the default of the holders. */
    public static BehaviourSettings platformOnly() {
        return PLATFORM_ONLY;
    }

    /** Reads {@code resolver}; null = {@link #platformOnly()}. */
    public static BehaviourSettings of(SettingsResolver resolver) {
        return resolver == null ? PLATFORM_ONLY : new BehaviourSettings(() -> resolver);
    }

    /**
     * Reads the resolver the supplier returns each time (null = the platform value). For wiring where the resolver bean
     * may not exist yet when the holder is built.
     */
    public static BehaviourSettings lazy(Supplier<SettingsResolver> resolver) {
        return resolver == null ? PLATFORM_ONLY : new BehaviourSettings(resolver);
    }

    /** True when a catalog is behind this instance (an override can apply). */
    public boolean hasCatalog() {
        return resolver.get() != null;
    }

    public boolean bool(String key, TenantScope scope, UUID agentId, BooleanSupplier platform) {
        return read(key, scope, platform::getAsBoolean, r -> r.getBool(key, SettingContext.forAgent(agentId)));
    }

    public int integer(String key, TenantScope scope, UUID agentId, IntSupplier platform) {
        return read(key, scope, platform::getAsInt, r -> r.getInt(key, SettingContext.forAgent(agentId)));
    }

    public Duration seconds(String key, TenantScope scope, UUID agentId, Supplier<Duration> platform) {
        return read(key, scope, platform, r -> r.getDuration(key, SettingContext.forAgent(agentId)));
    }

    /**
     * The catalog's answer for a flag, or null when there is none to give (no catalog, or the read failed): for a use site
     * whose platform value lives somewhere else (a process-wide component) and that only wants to say "override this".
     */
    public Boolean boolOrNull(String key, TenantScope scope, UUID agentId) {
        return readOrNull(key, scope, r -> r.getBool(key, SettingContext.forAgent(agentId)));
    }

    /** As {@link #boolOrNull} for a whole number. */
    public Integer integerOrNull(String key, TenantScope scope, UUID agentId) {
        return readOrNull(key, scope, r -> r.getInt(key, SettingContext.forAgent(agentId)));
    }

    private <T> T readOrNull(String key, TenantScope scope, java.util.function.Function<SettingsResolver, T> fromCatalog) {
        if (resolver.get() == null) {
            return null;
        }
        return read(key, scope, () -> null, fromCatalog);
    }

    private <T> T read(
            String key, TenantScope scope, Supplier<T> platform, java.util.function.Function<SettingsResolver, T> fromCatalog) {
        SettingsResolver catalog;
        try {
            catalog = resolver.get();
        } catch (RuntimeException e) {
            log.warn("The settings catalog is not available ({}); using the platform value of {}", e.toString(), key);
            return platform.get();
        }
        if (catalog == null) {
            return platform.get();
        }
        try {
            Supplier<T> read = () -> fromCatalog.apply(catalog);
            return scope == null ? read.get() : scope.get(read);
        } catch (RuntimeException e) {
            log.warn("Could not read setting {} ({}); using the platform value", key, e.toString());
            return platform.get();
        }
    }
}
