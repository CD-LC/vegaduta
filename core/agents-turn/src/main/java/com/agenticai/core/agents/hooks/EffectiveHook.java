package com.agenticai.core.agents.hooks;

/**
 * A hook as the runtime sees it: the validated definition plus the scope it came from.
 *
 * @param scope {@code tenant} (a workspace default) or {@code agent} (the agent's own list)
 * @param def the validated hook
 */
public record EffectiveHook(String scope, HookDefinition def) {

    public static final String SCOPE_TENANT = "tenant";
    public static final String SCOPE_AGENT = "agent";

    /** Unique across both scopes (a tenant hook and an agent hook may share an id). */
    public String key() {
        return scope + ":" + def.id();
    }
}
