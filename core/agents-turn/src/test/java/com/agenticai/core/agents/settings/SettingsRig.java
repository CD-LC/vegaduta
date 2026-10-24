package com.agenticai.core.agents.settings;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsContributor;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The real catalog of the given contributors over the real resolver and an in-memory store partitioned by tenant (no
 * cache, so a write is seen at once): a test sets an override the way an admin's write leaves it and watches the runtime
 * change. The same shape as {@link BehaviourCatalogFixture}, for a contributor that is not part of that catalog.
 */
public final class SettingsRig {

    public final InMemorySettingsStore store = new InMemorySettingsStore();
    private final DefaultSettingsResolver resolver;

    public SettingsRig(SettingsContributor... contributors) {
        SettingsProperties noCache = new SettingsProperties();
        noCache.setCacheTtl(Duration.ZERO);
        this.resolver = new DefaultSettingsResolver(new SettingsRegistry(List.of(contributors)), store, List.of(), noCache, null);
    }

    public DefaultSettingsResolver resolver() {
        return resolver;
    }

    /** A tenant-scope override, as the tenant admin's write leaves it. */
    public SettingsRig tenant(String tenantId, String key, Object value) {
        inTenant(tenantId, () -> store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", key, value, 1, "admin", Instant.now())));
        return this;
    }

    /** An agent-scope override. */
    public SettingsRig agent(String tenantId, UUID agentId, String key, Object value) {
        inTenant(
                tenantId,
                () -> store.saveCurrent(
                        new SettingOverride(SettingScope.AGENT, agentId.toString(), key, value, 1, "editor", Instant.now())));
        return this;
    }

    public static void inTenant(String tenantId, Runnable work) {
        BehaviourCatalogFixture.inTenant(tenantId, work);
    }

    public static void clear() {
        TenantContext.clear();
    }

    public static Set<String> none() {
        return Set.of();
    }
}
