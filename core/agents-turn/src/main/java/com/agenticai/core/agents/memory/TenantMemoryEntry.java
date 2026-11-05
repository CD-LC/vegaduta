package com.agenticai.core.agents.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One recalled or listed shared/tenant-wide memory row - see {@link TenantMemoryService}.
 * {@code trust}/{@code sourceTaint}/{@code supersededAt} as on {@link AgentMemoryEntry}.
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
    List<String> sourceTaint,
    Instant supersededAt
) {
    public TenantMemoryEntry {
        trust = trust == null ? MemoryTrust.QUARANTINED : trust;
        sourceTaint = sourceTaint == null ? List.of() : List.copyOf(sourceTaint);
    }

    /** A live row (not superseded) with its provenance. */
    public TenantMemoryEntry(
        UUID id, String subjectKey, String content, double distance, UUID createdByAgentId,
        Instant createdAt, Instant lastUsedAt, int useCount, MemoryTrust trust, List<String> sourceTaint
    ) {
        this(id, subjectKey, content, distance, createdByAgentId, createdAt, lastUsedAt, useCount, trust, sourceTaint, null);
    }

    public TenantMemoryEntry(
        UUID id, String subjectKey, String content, double distance, UUID createdByAgentId,
        Instant createdAt, Instant lastUsedAt, int useCount
    ) {
        this(id, subjectKey, content, distance, createdByAgentId, createdAt, lastUsedAt, useCount,
            MemoryTrust.TRUSTED, List.of());
    }
}
