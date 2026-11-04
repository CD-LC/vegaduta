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

    /** Hard delete (erasure); appends the ERASED event. False when the row did not exist. */
    boolean delete(UUID agentId, UUID itemId, MemoryEvent erased);

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
