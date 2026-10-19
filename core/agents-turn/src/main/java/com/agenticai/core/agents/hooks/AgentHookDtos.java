package com.agenticai.core.agents.hooks;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response shapes for the hooks REST endpoints. Hooks travel as the canonical maps {@link HookSchema#toMaps}
 * produces, so what a client reads back is exactly what it can write back.
 */
public final class AgentHookDtos {

    private AgentHookDtos() {
    }

    /**
     * What the server will accept, so a client builds its form from the server's own rules.
     *
     * @param actionsByPoint point name to the action names allowed there
     */
    public record Schema(
        int maxHooks,
        int defaultTimeoutMs,
        int minTimeoutMs,
        int maxTimeoutMs,
        List<String> points,
        Map<String, List<String>> actionsByPoint,
        List<String> builtinPatterns,
        List<String> trustTiers,
        List<String> sensitivities
    ) {
    }

    /**
     * {@code GET}/{@code PUT /api/agents/{agentId}/hooks}.
     *
     * @param enabled false when {@code app.agent-hooks.enabled} is off: the hooks can be read but are not enforced
     *     and cannot be saved
     * @param hooks this agent's own hooks
     * @param inherited the workspace default hooks that also apply (read-only here)
     * @param webhookHosts the hosts a webhook hook may call (workspace list merged with the operator's)
     */
    public record AgentHooksResponse(
        boolean enabled,
        List<Map<String, Object>> hooks,
        List<Map<String, Object>> inherited,
        List<String> webhookHosts,
        Instant updatedAt,
        Schema schema
    ) {
    }

    /**
     * {@code GET}/{@code PUT /api/settings/agent-hooks}.
     *
     * @param webhookHosts the workspace's own webhook allow-list
     * @param platformWebhookHosts the operator's allow-list (read-only; configured in the deployment)
     */
    public record TenantHooksResponse(
        boolean enabled,
        List<Map<String, Object>> hooks,
        List<String> webhookHosts,
        List<String> platformWebhookHosts,
        Instant updatedAt,
        Schema schema
    ) {
    }
}
