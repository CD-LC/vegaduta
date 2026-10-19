package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.capability.CapabilityAvailability;

/**
 * One row of {@code GET /api/agents/{id}/capabilities} - the wire shape of an
 * {@link com.agenticai.core.agents.capability.AgentCapability} for a specific
 * agent. {@code label}/{@code description}/{@code availabilityHint} are
 * server-supplied display text so the workbench can render a capability the
 * moment its bean exists, without a per-provider UI section or locale key.
 */
public record AgentCapabilityResponse(
    String key,
    String label,
    String description,
    String category,
    boolean enabled,
    CapabilityAvailability availability,
    String availabilityHint
) {
}
