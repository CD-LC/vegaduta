package com.agenticai.core.agents.memory.spi;

import java.util.List;

/**
 * Where a memory came from: its trust, the taint sources recorded when it was written (the same
 * list as the V676 {@code source_taint} column) and an optional writer label such as {@code
 * remember_fact} or {@code import}.
 */
public record MemoryProvenanceRecord(
        MemoryTrustLevel trust, List<String> sourceTaint, String writer) {

    /**
     * Writer labels of the nightly consolidation's proposals. A proposal carrying one must name its
     * sources; the store marks the row so that a person's approval also supersedes those sources.
     */
    public static final String WRITER_CONSOLIDATION_MERGE = "consolidation:merge";

    public static final String WRITER_CONSOLIDATION_SUMMARY = "consolidation:summary";

    /** A person-authored or clean-turn write. */
    public static final MemoryProvenanceRecord TRUSTED =
            new MemoryProvenanceRecord(MemoryTrustLevel.TRUSTED, List.of(), null);

    public MemoryProvenanceRecord {
        trust = trust == null ? MemoryTrustLevel.QUARANTINED : trust;
        sourceTaint = sourceTaint == null ? List.of() : List.copyOf(sourceTaint);
    }

    /**
     * The provenance of an item derived from {@code sources}: the meet of their trust and the
     * ordered union of their taint. No sources is QUARANTINED (nothing to inherit trust from).
     */
    public static MemoryProvenanceRecord derive(
            java.util.Collection<MemoryProvenanceRecord> sources, String writer) {
        if (sources == null || sources.isEmpty()) {
            return new MemoryProvenanceRecord(MemoryTrustLevel.QUARANTINED, List.of(), writer);
        }
        java.util.Set<String> taint = new java.util.LinkedHashSet<>();
        java.util.List<MemoryTrustLevel> levels = new java.util.ArrayList<>();
        for (MemoryProvenanceRecord source : sources) {
            levels.add(source.trust());
            taint.addAll(source.sourceTaint());
        }
        return new MemoryProvenanceRecord(
                MemoryTrustLevel.meet(levels), List.copyOf(taint), writer);
    }

    public boolean quarantined() {
        return trust != MemoryTrustLevel.TRUSTED;
    }
}
