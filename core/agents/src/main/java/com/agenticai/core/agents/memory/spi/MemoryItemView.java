package com.agenticai.core.agents.memory.spi;

import java.util.UUID;

/**
 * A recalled memory as the prompt path sees it: id, kind, text and relevance score. Only TRUSTED
 * items exist as views (ACTIVE is implied by the read port); building one from a quarantined
 * provenance throws, so a source cannot hand an untrusted item to the composer.
 */
public record MemoryItemView(UUID id, MemoryKind kind, String text, double score, MemoryProvenanceRecord provenance) {

    public MemoryItemView {
        if (id == null || kind == null || text == null) {
            throw new IllegalArgumentException("id, kind and text are required");
        }
        provenance = provenance == null ? MemoryProvenanceRecord.TRUSTED : provenance;
        if (provenance.trust() != MemoryTrustLevel.TRUSTED) {
            throw new IllegalArgumentException("only TRUSTED memory can be recalled");
        }
    }
}
