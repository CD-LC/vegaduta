package com.agenticai.core.agents.version;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/agents/{agentId}/versions/{versionId}/promote} -
 * C1's promote verb spelled as an action, layered over the same
 * {@link AgentVersionService#setLabel} machinery the older
 * {@code PUT .../label} endpoint drives. PRODUCTION still runs the eval
 * gate; {@code force} + {@code reason} still require MANAGE_AGENTS.
 */
public record AgentVersionPromoteRequest(
    @NotNull AgentVersionLabel label,
    Boolean force,
    String reason
) {
}
