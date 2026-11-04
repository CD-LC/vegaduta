package com.agenticai.core.agents.memorystore.decay;

import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable view of one memory row as the planner needs it. Carries no text: planning is
 * structural, and the text never needs to enter this package.
 *
 * @param kind free-form fact kind (for the per-kind half-life), e.g. {@code preference}
 * @param status only {@link Status#ACTIVE} rows are planned
 * @param personAuthored typed by a person (Memory page) rather than learned by the agent
 * @param sources lineage: ids this row was derived from, empty for a primary row
 * @param generation 0 for a primary row, max(source generations)+1 for a derived one
 * @param modelGenerated the text was produced by a model (a summary)
 * @param humanReconfirmed a person re-confirmed a model-generated row; lifts the summarise exclusion
 */
public record ItemSnapshot(
        UUID id,
        UUID agentId,
        String kind,
        MemoryTrustLevel trust,
        Status status,
        boolean pinned,
        boolean personAuthored,
        double importance,
        int uses,
        Instant createdAt,
        Instant lastUsedAt,
        List<UUID> sources,
        int generation,
        boolean modelGenerated,
        boolean humanReconfirmed) {

    /** Lifecycle of a row as the store reports it. */
    public enum Status {
        ACTIVE,
        PENDING,
        TOMBSTONED
    }

    public ItemSnapshot {
        if (id == null || createdAt == null) {
            throw new IllegalArgumentException("id and createdAt are required");
        }
        kind = kind == null ? "" : kind;
        // Fail closed: an unknown trust is the lower one.
        trust = trust == null ? MemoryTrustLevel.QUARANTINED : trust;
        status = status == null ? Status.PENDING : status;
        sources = sources == null ? List.of() : List.copyOf(sources);
        generation = Math.max(0, generation);
        uses = Math.max(0, uses);
    }

    /** True when the text came from a model, whatever flag the store set (lineage implies it). */
    boolean derivedByModel() {
        return modelGenerated || generation > 0 || !sources.isEmpty();
    }
}
