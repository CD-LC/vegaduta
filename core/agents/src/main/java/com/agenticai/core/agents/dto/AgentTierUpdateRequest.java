package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.core.AgentTier;
import jakarta.validation.constraints.NotNull;

/** {@code PATCH /api/agents/{id}/tier}'s request body - see {@link AgentTier}'s javadoc. */
public record AgentTierUpdateRequest(@NotNull AgentTier tier) {
}
