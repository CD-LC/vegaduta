package com.agenticai.core.agents.memory.spi;

import java.util.List;

/**
 * Where a memory came from: its trust, the taint sources recorded when it was written (the same
 * list as the V676 {@code source_taint} column) and an optional writer label such as
 * {@code remember_fact} or {@code import}.
 */
public record MemoryProvenanceRecord(MemoryTrustLevel trust, List<String> sourceTaint, String writer) {

    /** A person-authored or clean-turn write. */
    public static final MemoryProvenanceRecord TRUSTED =
            new MemoryProvenanceRecord(MemoryTrustLevel.TRUSTED, List.of(), null);

    public MemoryProvenanceRecord {
        trust = trust == null ? MemoryTrustLevel.QUARANTINED : trust;
        sourceTaint = sourceTaint == null ? List.of() : List.copyOf(sourceTaint);
    }

    public boolean quarantined() {
        return trust != MemoryTrustLevel.TRUSTED;
    }
}
