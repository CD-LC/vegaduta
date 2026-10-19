package com.agenticai.core.agents.version;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/agents/{agentId}/versions} - C1's explicit
 * {@code publish(entityId, changeNote)}. {@code changeNote} is the
 * tenant-written "what changed and why" the version list renders; optional,
 * and capped at the column's 500 chars by the snapshot factory itself.
 */
public record AgentVersionPublishRequest(
    @Size(max = 500) String changeNote
) {
}
