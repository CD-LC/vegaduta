package com.agenticai.core.agents.tools.scratchpad;

import java.time.Instant;
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

    /**
     * The upsert lookup behind {@code AgentScratchpadService#write}; a null sessionId reads the
     * agent-long-term note.
     */
    Optional<ScratchpadNote> findByAgentIdAndSessionIdAndKey(
            UUID agentId, UUID sessionId, String key);

    /**
     * How many notes the {@code (agentId, sessionId)} scope already holds; a null sessionId counts
     * the agent-long-term scope.
     */
    long countByAgentIdAndSessionId(UUID agentId, UUID sessionId);

    /**
     * Returns rows removed (0 or 1 - the scope+key is unique); a null sessionId deletes the
     * agent-long-term note.
     */
    long deleteByAgentIdAndSessionIdAndKey(UUID agentId, UUID sessionId, String key);

    /**
     * The stalest note in the scope - the LRU eviction candidate on a full pad; null sessionId
     * reads the agent-long-term scope.
     */
    Optional<ScratchpadNote> findFirstByAgentIdAndSessionIdOrderByUpdatedAtAsc(
            UUID agentId, UUID sessionId);

    /**
     * TTL sweep for a scope: removes every note not touched since {@code cutoff}; returns the count
     * removed.
     */
    long deleteByAgentIdAndSessionIdAndUpdatedAtBefore(
            UUID agentId, UUID sessionId, Instant cutoff);

    /** The caller's own agent-pad note under {@code key}. */
    Optional<ScratchpadNote> findByAgentIdAndSessionIdIsNullAndSubjectKeyAndKey(
            UUID agentId, String subjectKey, String key);

    /** Every agent-pad note under {@code key}, whoever owns it (operator review paths only). */
    List<ScratchpadNote> findByAgentIdAndSessionIdIsNullAndKey(UUID agentId, String key);

    /** How many agent-pad notes one caller holds: the cap is per caller, not per agent. */
    long countByAgentIdAndSessionIdIsNullAndSubjectKey(UUID agentId, String subjectKey);

    /** The caller's stalest agent-pad note: the eviction candidate. */
    Optional<ScratchpadNote> findFirstByAgentIdAndSessionIdIsNullAndSubjectKeyOrderByUpdatedAtAsc(
            UUID agentId, String subjectKey);

    /** Data-subject erasure: every note (either pad, any agent) owned by the subject. */
    long deleteBySubjectKey(String subjectKey);

    /**
     * What the model may see for one caller: the session's notes (those owned by the caller or by
     * nobody, since the session id is already per caller) and the caller's own agent-pad notes.
     * Notes with a null owner on the agent pad (pre-V680) are never returned here.
     */
    @Query(
            "select n from ScratchpadNote n where n.agentId = :agentId"
                    + " and n.trust = 'TRUSTED'"
                    + " and ((n.sessionId = :sessionId and (n.subjectKey = :subject or n.subjectKey is null))"
                    + " or (n.sessionId is null and n.subjectKey = :subject))"
                    + " order by n.updatedAt desc")
    List<ScratchpadNote> findVisibleTo(
            @Param("agentId") UUID agentId,
            @Param("sessionId") UUID sessionId,
            @Param("subject") String subject);

    /**
     * Operator view, every owner: the session's notes AND the agent-long-term ({@code sessionId IS
     * NULL}) ones, newest-updated first. Never use this for a model-facing read.
     */
    @Query(
            "select n from ScratchpadNote n where n.agentId = :agentId"
                    + " and n.trust = 'TRUSTED'"
                    + " and (n.sessionId = :sessionId or n.sessionId is null)"
                    + " order by n.updatedAt desc")
    List<ScratchpadNote> findVisible(
            @Param("agentId") UUID agentId, @Param("sessionId") UUID sessionId);

    /** Notes held for review (written on a turn that handled untrusted content), newest first. */
    @Query(
            "select n from ScratchpadNote n where n.agentId = :agentId and n.trust = 'QUARANTINED'"
                    + " order by n.updatedAt desc")
    List<ScratchpadNote> findQuarantined(@Param("agentId") UUID agentId);
}
