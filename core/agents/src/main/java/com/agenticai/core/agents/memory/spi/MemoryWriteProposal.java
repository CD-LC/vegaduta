package com.agenticai.core.agents.memory.spi;

import java.util.List;
import java.util.UUID;

/**
 * A request to remember something. It carries no status and no trust of its own: those are
 * decided by the sink from {@code provenance} and the kind, and the result can only be PENDING or
 * QUARANTINED. {@code modelGenerated} marks text a model wrote (as opposed to a person or a tool
 * result). {@code sources} is the lineage of a derived proposal (a distilled procedure, a
 * self-assessment): the store takes the lowest trust of them, the union of their taint and
 * {@code max(generation) + 1}; empty for a proposal built straight from an event.
 */
public record MemoryWriteProposal(
        UUID agentId,
        String subjectKey,
        MemoryKind kind,
        String text,
        MemoryProvenanceRecord provenance,
        UUID turnId,
        boolean modelGenerated,
        List<MemorySourceRef> sources) {

    /** A proposal that is not derived from other memory rows (no lineage). */
    public MemoryWriteProposal(
            UUID agentId,
            String subjectKey,
            MemoryKind kind,
            String text,
            MemoryProvenanceRecord provenance,
            UUID turnId,
            boolean modelGenerated) {
        this(agentId, subjectKey, kind, text, provenance, turnId, modelGenerated, List.of());
    }

    public MemoryWriteProposal {
        if (agentId == null || kind == null || text == null || text.isBlank()) {
            throw new IllegalArgumentException("agentId, kind and non-blank text are required");
        }
        provenance = provenance == null ? new MemoryProvenanceRecord(null, null, null) : provenance;
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
