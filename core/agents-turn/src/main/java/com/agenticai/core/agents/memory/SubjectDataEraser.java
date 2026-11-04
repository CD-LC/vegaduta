package com.agenticai.core.agents.memory;

/**
 * A store of per-user agent data outside {@code agent_memory} / {@code tenant_memory} / the graph that
 * must be wiped when a data subject is erased (self-service {@code DELETE /api/memory}, or the operator's
 * contact erasure). Lives in the turn module so the callers in {@code agents-turn} and {@code app} can
 * reach a store owned by a module they must not depend on (today: the agent scratchpad in {@code
 * agents-tools}). Implementations must erase even when their feature flag is off: data written while it was
 * on is still personal data.
 */
public interface SubjectDataEraser {

    /** Short stable name of the store, used in erasure reports ("scratchpad"). */
    String store();

    /**
     * Deletes everything this store holds for {@code subjectKey} (the same key {@code
     * AgentMemoryService.forgetAllForSubject} takes) across all agents; returns the rows removed.
     */
    int eraseSubject(String subjectKey);
}
