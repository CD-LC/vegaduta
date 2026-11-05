package com.agenticai.core.agents.learning.consolidation;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The read-only surface the nightly consolidation sees of the unified memory store ({@code
 * memory_item}: EPISODIC, PROCEDURAL and SELF_MODEL rows), the twin of {@link
 * ConsolidationFactSource}. Looking is all it can do; filing a merge or summary goes through the
 * {@code MemoryProposalSink}, which can only create PENDING rows. The activate, transition and
 * delete operations of the store are not on this type, so the sweep cannot approve, supersede or
 * erase an item.
 */
public interface ConsolidationItemSource {

    /** Up to {@code limit} agents with ACTIVE items in the current tenant. */
    List<UUID> agents(int limit);

    /** The agent's ACTIVE items (all kinds). */
    List<Item> items(UUID agentId);

    /**
     * Item ids that already feed a PENDING consolidation proposal, so the same merge or summary is
     * not filed twice.
     */
    Set<UUID> sourcesOfPendingProposals(UUID agentId);

    /**
     * One ACTIVE item as the planner needs it.
     *
     * @param importance the stored importance prior; 0 means it was never assigned
     * @param pinned a person asked to keep it
     * @param personAuthored written by a person (an edit), not learned by the agent
     * @param modelGenerated a model wrote the text
     * @param humanConfirmed a person approved it (every ACTIVE item was; kept explicit for the
     *     planner)
     * @param useCount recorded recalls (the unified recall does not record usage yet)
     */
    record Item(
            UUID id,
            UUID agentId,
            MemoryKind kind,
            String content,
            int generation,
            boolean modelGenerated,
            boolean humanConfirmed,
            boolean personAuthored,
            boolean pinned,
            double importance,
            int useCount,
            Instant createdAt,
            Instant lastUsedAt,
            Instant approvedAt) {}
}
