package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence port of the unified-memory store. Every method that changes state also appends its
 * {@link MemoryEvent} in the same unit of work, so there is no state change without an event.
 * All methods are scoped to the tenant of the calling thread and fail closed without one.
 *
 * <p>{@link #activate} is the only way an item becomes TRUSTED and ACTIVE; only
 * {@link MemoryItemReviewService} calls it (enforced by {@code MemoryStoreArchTest}).
 */
public interface MemoryItemStorage {

    /** An item with its distance to the query embedding (0 = identical). */
    record Recalled(MemoryItem item, double distance, double importance) {}

    /** Inserts a QUARANTINED + PENDING row, its lineage and its CREATED event. */
    void insertPending(MemoryItem item, float[] embedding, List<MemoryItemSource> lineage, MemoryEvent created);

    Optional<MemoryItem> find(UUID agentId, UUID itemId);

    List<MemoryItem> listByStatus(UUID agentId, MemoryItemStatus status, int limit);

    /**
     * An agent's rows, newest first. {@code status} null means every status, {@code kind} null
     * means every kind (the REST list filters on both).
     */
    List<MemoryItem> listByAgent(UUID agentId, MemoryItemStatus status, MemoryKind kind, int limit);

    /**
     * Every row whose {@code subject_keys} contain {@code subjectKey}, across all of the tenant's
     * agents and every status (the per-user export). Ordered newest first.
     */
    List<MemoryItem> listForSubject(String subjectKey, int limit);

    List<MemoryItemSource> lineage(UUID itemId);

    List<MemoryEvent> events(UUID itemId);

    int countByStatus(UUID agentId, MemoryItemStatus status);

    /** PENDING plus ACTIVE rows. */
    int countLive(UUID agentId);

    /** CREATED events for the agent at or after {@code since}. */
    int countCreatedSince(UUID agentId, Instant since);

    /**
     * PENDING to ACTIVE + TRUSTED with {@code approvedBy} recorded, plus the event; when
     * {@code supersedes} is set, that predecessor goes ACTIVE to SUPERSEDED (with {@code predecessorEvent})
     * in the same unit of work. False when the row was not PENDING and QUARANTINED any more (a race).
     */
    boolean activate(
            UUID agentId,
            UUID itemId,
            String approvedBy,
            Instant at,
            MemoryEvent approved,
            UUID supersedes,
            MemoryEvent predecessorEvent);

    /** Moves a row between statuses (never to ACTIVE) and appends the event; false when the row was not in {@code from}. */
    boolean transition(
            UUID agentId, UUID itemId, Set<MemoryItemStatus> from, MemoryItemStatus to, MemoryEvent event);

    /**
     * Sets or clears the {@code pinned} flag of one row and appends the event in the same unit of work. False when the
     * row is not the agent's or already has that value (nothing changed, no event).
     */
    boolean setPinned(UUID agentId, UUID itemId, boolean pinned, MemoryEvent event);

    /** Hard delete (erasure); appends the ERASED event. False when the row did not exist. */
    boolean delete(UUID agentId, UUID itemId, MemoryEvent erased);

    /**
     * Erasure cascade (WP6): deletes the item and every item derived from it (the lineage children
     * found through {@code memory_item_source}, recursively), each erased row's lineage (FK cascade)
     * and its event history, and then appends one ERASED tombstone event per erased id, so the log
     * still shows that something was erased without keeping what it was. Only rows of {@code agentId}
     * are touched. Returns the erased ids, empty when the root row did not exist.
     */
    List<UUID> eraseTree(UUID agentId, UUID itemId, MemoryActor actor, Instant at);

    /**
     * Subject erasure (DPDP, ADR-0028 section 12): runs {@link #eraseTree} for every item whose
     * {@code subject_keys} name {@code subjectKey}, across all of the tenant's agents and every status, until none is
     * left. Each tree (the item, everything derived from it, their lineage and history) goes in one unit of work and
     * leaves one content-free ERASED tombstone event per id. Works with every feature flag off. Returns the erased ids.
     */
    default List<UUID> eraseSubject(String subjectKey, MemoryActor actor, Instant at) {
        List<UUID> erased = new java.util.ArrayList<>();
        if (subjectKey == null || subjectKey.isBlank()) {
            return erased;
        }
        while (true) {
            List<MemoryItem> batch = listForSubject(subjectKey, 500);
            int before = erased.size();
            for (MemoryItem item : batch) {
                erased.addAll(eraseTree(item.agentId(), item.id(), actor, at));
            }
            if (batch.isEmpty() || erased.size() == before) {
                return erased;
            }
        }
    }

    /** Appends an event that is not tied to a row change (a refused proposal). */
    void appendEvent(MemoryEvent event);

    /**
     * ACTIVE, TRUSTED, unexpired rows of one kind nearest to {@code embedding}. {@code subjectKey}
     * restricts to rows about that subject (null = agent-wide, no subject filter). The predicate is
     * in the query, not only in the index.
     */
    List<Recalled> recall(
            UUID agentId, MemoryKind kind, String subjectKey, float[] embedding, int limit, Instant now);
}
