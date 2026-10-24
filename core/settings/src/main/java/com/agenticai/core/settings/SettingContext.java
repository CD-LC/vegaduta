package com.agenticai.core.settings;

import com.agenticai.core.security.TenantContext;
import java.util.UUID;

/**
 * Whose settings to resolve. {@code tenantId} may be null (taken from the thread's {@link TenantContext}); when it
 * is given and differs from the thread's tenant, no tenant or agent override is applied (the store can only read the
 * calling thread's tenant, and one tenant's override must never reach another), the platform value is returned.
 */
public record SettingContext(String tenantId, UUID agentId) {

    private static final SettingContext NONE = new SettingContext(null, null);

    /** The current thread's tenant, no agent. */
    public static SettingContext tenant() {
        return NONE;
    }

    /** The current thread's tenant and this agent (null = tenant level). */
    public static SettingContext forAgent(UUID agentId) {
        return agentId == null ? NONE : new SettingContext(null, agentId);
    }

    public static SettingContext of(String tenantId, UUID agentId) {
        return new SettingContext(tenantId, agentId);
    }

    /** The tenant this context resolves for, or null when the thread has none or the context names another one. */
    String effectiveTenant() {
        String current = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (current == null) {
            return null;
        }
        if (tenantId != null && !tenantId.equals(current)) {
            return null;
        }
        return current;
    }
}
