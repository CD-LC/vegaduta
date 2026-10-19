package com.agenticai.core.agents.memory;

import java.time.Instant;
import java.util.UUID;

/** One recalled or listed shared/tenant-wide memory row - see {@link TenantMemoryService}. */
public record TenantMemoryEntry(
    UUID id,
    String subjectKey,
    String content,
    double distance,
    UUID createdByAgentId,
    Instant createdAt,
    Instant lastUsedAt,
    int useCount
) {
}
