package com.agenticai.core.agents.tools.scratchpad;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * No {@code tenantId} parameter anywhere by design - schema-per-tenant means every query here is
 * already scoped to the caller's schema by the connection's search_path. Same shape as {@code
 * CustomCodeToolRepository}.
 *
 * <p>The derived {@code ...SessionId...} methods are called with a {@code null} sessionId for
 * agent-long-term notes: Spring Data turns a null argument on an equality path into {@code IS
 * NULL}, so {@code findByAgentIdAndSessionIdAndKey(agent, null, key)} reads the long-term note.
 */
public interface ScratchpadNoteRepository extends JpaRepository<ScratchpadNote, UUID> {

    /** The upsert lookup behind {@code AgentScratchpadService#write}; a null sessionId reads the agent-long-term note. */
    Optional<ScratchpadNote> findByAgentIdAndSessionIdAndKey(UUID agentId, UUID sessionId, String key);

    /** How many notes the {@code (agentId, sessionId)} scope already holds; a null sessionId counts the agent-long-term scope. */
    long countByAgentIdAndSessionId(UUID agentId, UUID sessionId);

    /** Returns rows removed (0 or 1 - the scope+key is unique); a null sessionId deletes the agent-long-term note. */
    long deleteByAgentIdAndSessionIdAndKey(UUID agentId, UUID sessionId, String key);

    /**
     * Everything the agent can see from {@code sessionId}: that session's notes AND the
     * agent-long-term ({@code sessionId IS NULL}) ones, newest-updated first. A null {@code
     * sessionId} argument yields just the long-term notes.
     */
    @Query(
            "select n from ScratchpadNote n where n.agentId = :agentId"
                    + " and (n.sessionId = :sessionId or n.sessionId is null)"
                    + " order by n.updatedAt desc")
    List<ScratchpadNote> findVisible(@Param("agentId") UUID agentId, @Param("sessionId") UUID sessionId);
}
