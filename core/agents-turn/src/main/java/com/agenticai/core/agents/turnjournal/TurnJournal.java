package com.agenticai.core.agents.turnjournal;

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

    /** Events of one turn of one agent in seq order; empty when unknown. */
    List<TurnEvent> read(UUID agentId, UUID turnId);
}
