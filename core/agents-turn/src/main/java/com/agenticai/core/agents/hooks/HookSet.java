package com.agenticai.core.agents.hooks;

import java.util.List;

/**
 * The hooks in force for one agent: the workspace defaults first, then the agent's own, plus the workspace's
 * webhook allow-list. Defaults come first so they always evaluate first and can never be displaced by an
 * agent-level hook; an agent can add hooks but cannot remove or reorder the workspace's.
 *
 * @param hooks tenant defaults followed by agent hooks, in saved order
 * @param webhookHosts the tenant's own webhook allow-list
 */
public record HookSet(List<EffectiveHook> hooks, List<String> webhookHosts) {

    public static final HookSet EMPTY = new HookSet(List.of(), List.of());

    public HookSet {
        hooks = hooks == null ? List.of() : List.copyOf(hooks);
        webhookHosts = webhookHosts == null ? List.of() : List.copyOf(webhookHosts);
    }

    public boolean isEmpty() {
        return hooks.isEmpty();
    }
}
