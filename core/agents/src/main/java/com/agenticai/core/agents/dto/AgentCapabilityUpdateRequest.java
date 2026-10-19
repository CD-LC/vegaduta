package com.agenticai.core.agents.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PATCH /api/agents/{id}/capabilities/{key}} - the generic
 * replacement for the deleted per-provider toggle request DTOs
 * (AgentSarvamToggleRequest, AgentE2bToggleRequest,
 * AgentXaiServerToolsToggleRequest, AgentCodeWorkspaceToggleRequest).
 */
public record AgentCapabilityUpdateRequest(@NotNull Boolean enabled) {
}
