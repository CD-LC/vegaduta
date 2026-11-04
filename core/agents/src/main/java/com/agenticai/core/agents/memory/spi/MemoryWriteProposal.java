package com.agenticai.core.agents.memory.spi;

import java.util.UUID;

/**
 * A request to remember something. It carries no status and no trust of its own: those are
 * decided by the sink from {@code provenance} and the kind, and the result can only be PENDING or
 * QUARANTINED. {@code modelGenerated} marks text a model wrote (as opposed to a person or a tool
 * result).
 */
public record MemoryWriteProposal(
        UUID agentId,
        String subjectKey,
        MemoryKind kind,
        String text,
        MemoryProvenanceRecord provenance,
        UUID turnId,
        boolean modelGenerated) {

    public MemoryWriteProposal {
        if (agentId == null || kind == null || text == null || text.isBlank()) {
            throw new IllegalArgumentException("agentId, kind and non-blank text are required");
        }
        provenance = provenance == null ? new MemoryProvenanceRecord(null, null, null) : provenance;
    }
}
