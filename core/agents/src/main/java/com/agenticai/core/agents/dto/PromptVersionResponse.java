package com.agenticai.core.agents.dto;

import com.agenticai.core.agents.core.PromptVersion;
import java.time.Instant;
import java.util.UUID;

/**
 * Master-Roadmap-2026.md item #5 (prompt-template versioning) - one entry
 * in {@code GET /api/agents/{agentId}/prompt/versions}' list response.
 */
public record PromptVersionResponse(
    UUID id,
    String systemPrompt,
    Instant createdAt
) {
    public static PromptVersionResponse from(PromptVersion version) {
        return new PromptVersionResponse(version.getId(), version.getSystemPrompt(), version.getCreatedAt());
    }
}
