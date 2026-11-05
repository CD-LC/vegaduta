package com.agenticai.core.agents.memorystore;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The read-only view of {@code memory_item} the nightly consolidation gets (ADR-0028 WP5c). It is a
 * port of its own, not {@link MemoryItemStorage}: the sweep must be unable to activate, transition
 * or delete an item (the promotion rule bars it from {@code MemoryItemStorage} and the review
 * service), so all it can do with the unified store is look, and file proposals through the
 * {@code MemoryProposalSink}. Every method is scoped to the tenant of the calling thread.
 */
public interface MemoryItemConsolidationReader {

    /**
     * One ACTIVE item with the columns the salience function needs.
     *
     * @param importance the stored prior ({@code importance} column; 0 means "never assigned")
     * @param useCount recalls recorded for the item (the unified recall does not record usage yet, so 0)
     * @param lastUsedAt last recorded use, or null
     */
    record Row(MemoryItem item, double importance, boolean pinned, int useCount, Instant lastUsedAt) {}

    /** Up to {@code limit} agents that have at least one ACTIVE item in this tenant. */
    List<UUID> agentIds(int limit);

    /** The agent's ACTIVE items (any kind; ACTIVE implies TRUSTED), oldest first, at most {@code limit}. */
    List<Row> activeItems(UUID agentId, int limit);

    /**
     * Ids of the agent's items that are already a lineage source of a PENDING consolidation
     * proposal, so a nightly run does not file the same merge or summary again while a person has
     * not decided on it yet.
     */
    Set<UUID> sourcesOfPendingProposals(UUID agentId);
}
