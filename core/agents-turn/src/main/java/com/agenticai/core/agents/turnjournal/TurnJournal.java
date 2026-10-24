package com.agenticai.core.agents.turnjournal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only, hash-chained per-turn journal (ADR-0020). Implementations assign {@code seq} and
 * the chain hashes themselves so callers cannot forge them.
 */
public interface TurnJournal {

    /** Appends the next event of {@code turnId} and returns it with its assigned seq and hashes. */
    TurnEvent append(
            UUID agentId, UUID turnId, TurnEventType type, String payloadHash, String summary);

    /**
     * As {@link #append(UUID, UUID, TurnEventType, String, String)}, also recording which chat session the turn
     * belongs to so the turn can be found by session ({@link #listBySession}). The session id is an index, not part
     * of the hash chain (the INTAKE summary carries a digest of it, which is). An implementation that does not keep
     * the session ignores it.
     */
    default TurnEvent append(
            UUID agentId,
            UUID turnId,
            String sessionId,
            TurnEventType type,
            String payloadHash,
            String summary) {
        return append(agentId, turnId, type, payloadHash, summary);
    }

    /** Events of one turn of one agent in seq order; empty when unknown. */
    List<TurnEvent> read(UUID agentId, UUID turnId);

    /**
     * The turns recorded for one chat session of one agent, newest first, at most {@code limit}. Empty when the
     * session is unknown or the implementation does not index sessions.
     */
    default List<TurnSummary> listBySession(UUID agentId, String sessionId, int limit) {
        return List.of();
    }

    /**
     * Retention: deletes WHOLE turns (every event of a turn, never a part of its chain) whose last event is older
     * than {@code cutoff}, oldest first, at most {@code maxTurns} turns. Returns the number of events removed;
     * 0 means nothing more is due. The default keeps everything.
     */
    default int purgeOlderThan(Instant cutoff, int maxTurns) {
        return 0;
    }
}
