package com.agenticai.core.agents.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One recalled or listed shared/tenant-wide memory row - see {@link TenantMemoryService}.
 * {@code trust}/{@code sourceTaint} as on {@link AgentMemoryEntry}.
 */
public record TenantMemoryEntry(
    UUID id,
    String subjectKey,
    String content,
    double distance,
    UUID createdByAgentId,
    Instant createdAt,
    Instant lastUsedAt,
    int useCount,
    MemoryTrust trust,
    List<String> sourceTaint
) {
    public TenantMemoryEntry {
        trust = trust == null ? MemoryTrust.QUARANTINED : trust;
        sourceTaint = sourceTaint == null ? List.of() : List.copyOf(sourceTaint);
    }

    public TenantMemoryEntry(
        UUID id, String subjectKey, String content, double distance, UUID createdByAgentId,
        Instant createdAt, Instant lastUsedAt, int useCount
    ) {
        this(id, subjectKey, content, distance, createdByAgentId, createdAt, lastUsedAt, useCount,
            MemoryTrust.TRUSTED, List.of());
    }
}
