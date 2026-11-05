package com.agenticai.core.agents.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One recalled or listed long-term memory row - see {@link AgentMemoryService}. {@code trust} and
 * {@code sourceTaint} are the row's provenance: a QUARANTINED row (written on a tainted turn) is
 * never recalled until a person approves it; an approved row keeps its {@code sourceTaint}. {@code
 * supersededAt} is set on a fact a newer one replaced (V685): it is kept, listed and exported, and
 * left out of recall.
 */
public record AgentMemoryEntry(
        UUID id,
        UUID agentId,
        String subjectKey,
        String content,
        double distance,
        Instant createdAt,
        Instant lastUsedAt,
        int useCount,
        MemoryTrust trust,
        List<String> sourceTaint,
        Instant supersededAt) {
    public AgentMemoryEntry {
        trust = trust == null ? MemoryTrust.QUARANTINED : trust;
        sourceTaint = sourceTaint == null ? List.of() : List.copyOf(sourceTaint);
    }

    /** A live row (not superseded) with its provenance. */
    public AgentMemoryEntry(
            UUID id,
            UUID agentId,
            String subjectKey,
            String content,
            double distance,
            Instant createdAt,
            Instant lastUsedAt,
            int useCount,
            MemoryTrust trust,
            List<String> sourceTaint) {
        this(
                id,
                agentId,
                subjectKey,
                content,
                distance,
                createdAt,
                lastUsedAt,
                useCount,
                trust,
                sourceTaint,
                null);
    }

    /** A person-authored (TRUSTED, no taint) row. */
    public AgentMemoryEntry(
            UUID id,
            UUID agentId,
            String subjectKey,
            String content,
            double distance,
            Instant createdAt,
            Instant lastUsedAt,
            int useCount) {
        this(
                id,
                agentId,
                subjectKey,
                content,
                distance,
                createdAt,
                lastUsedAt,
                useCount,
                MemoryTrust.TRUSTED,
                List.of());
    }
}
