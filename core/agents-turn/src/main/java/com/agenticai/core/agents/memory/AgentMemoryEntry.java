package com.agenticai.core.agents.memory;

import java.time.Instant;
import java.util.UUID;

/** One recalled or listed long-term memory row - see {@link AgentMemoryService}. */
public record AgentMemoryEntry(
    UUID id,
    UUID agentId,
    String subjectKey,
    String content,
    double distance,
    Instant createdAt,
    Instant lastUsedAt,
    int useCount
) {
}
