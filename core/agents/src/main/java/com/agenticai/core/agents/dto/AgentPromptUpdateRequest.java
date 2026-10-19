package com.agenticai.core.agents.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH /api/agents/{id}/prompt - consumer-facing capability ("consumers
 * should be able to edit the agent prompt") to change an existing agent's
 * persona/system prompt after creation. Same edit-lock gate as every other
 * after-creation PATCH on this resource (see {@code
 * AgentService#assertEditable}'s javadoc) - a locked FREE-tier agent past
 * its 24h grace window gets a 403 here too, same as changing its model.
 */
public record AgentPromptUpdateRequest(
    // Same 32768-char cap as AgentCreateRequest.systemPrompt - the create
    // and update paths must enforce the identical bound.
    @NotBlank(message = "systemPrompt is required")
    @Size(max = 32768, message = "systemPrompt must be at most 32768 characters") String systemPrompt
) {
}
