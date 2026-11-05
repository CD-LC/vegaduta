package com.agenticai.core.agents.memorystore;

import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code memory.unified.enabled} read from the settings catalog as one lower level of the
 * unified-memory gate. Two instances are registered: the tenant level (the workspace's value,
 * ignoring any agent override) and the agent level (the agent's own override, else the workspace's,
 * else the platform's). Each can only veto: the gate ANDs them, so an agent saying yes never
 * outvotes a tenant saying no. No resolver bean means no veto (the platform flag still decides); a
 * resolver that throws propagates, and the gate then counts the level as off.
 */
final class SettingsMemoryFlagOverride implements MemoryFlagOverride {

    private final ObjectProvider<SettingsResolver> settings;
    private final boolean agentLevel;

    private SettingsMemoryFlagOverride(
            ObjectProvider<SettingsResolver> settings, boolean agentLevel) {
        this.settings = settings;
        this.agentLevel = agentLevel;
    }

    static SettingsMemoryFlagOverride tenantLevel(ObjectProvider<SettingsResolver> settings) {
        return new SettingsMemoryFlagOverride(settings, false);
    }

    static SettingsMemoryFlagOverride agentLevel(ObjectProvider<SettingsResolver> settings) {
        return new SettingsMemoryFlagOverride(settings, true);
    }

    @Override
    public boolean allows(UUID agentId) {
        SettingsResolver resolver = settings.getIfAvailable();
        if (resolver == null) {
            return true;
        }
        SettingContext ctx =
                agentLevel ? SettingContext.forAgent(agentId) : SettingContext.tenant();
        return resolver.getBool(MemoryUnifiedSettings.ENABLED_KEY, ctx);
    }
}
