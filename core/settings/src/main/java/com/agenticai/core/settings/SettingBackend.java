package com.agenticai.core.settings;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Port for keys whose tenant and agent overrides already have a home (the seven {@code agent.limits.*} keys read and
 * write {@code tenant_runtime_limits} and the {@code agent} table, exactly as {@code /api/agents/{id}/runtime-limits}
 * does), so the catalog and the older endpoints can never disagree. Values are canonical ({@link SettingType}).
 *
 * <p>All methods run on the calling thread, in the current tenant. Access checks are the caller's
 * ({@link SettingsService}); the backend validates nothing beyond what its storage needs.
 */
public interface SettingBackend {

    /** The keys this backend stores. */
    Set<String> keys();

    /** The current tenant's overrides among {@link #keys()} (only keys that are set). */
    Map<String, Object> tenantValues();

    /** One agent's overrides among {@link #keys()} (only keys that are set). */
    Map<String, Object> agentValues(UUID agentId);

    /** Sets (value) or clears (null) the tenant override of one key. */
    void writeTenant(String key, Object value);

    /** Sets (value) or clears (null) one agent's override of one key. */
    void writeAgent(UUID agentId, String key, Object value);
}
