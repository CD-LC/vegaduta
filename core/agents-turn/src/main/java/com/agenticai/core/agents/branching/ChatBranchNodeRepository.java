package com.agenticai.core.agents.branching;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@link ChatBranchNode}; used only through {@link JpaChatBranchStore}. */
public interface ChatBranchNodeRepository extends JpaRepository<ChatBranchNode, UUID> {

    List<ChatBranchNode> findByAgentIdAndSessionIdOrderByCreatedAtAsc(
            UUID agentId, String sessionId);

    /**
     * Retention: every node of every session whose branching state was last touched before {@code
     * cutoff}. Run BEFORE the session rows go.
     */
    @Modifying
    @Query(
            "delete from ChatBranchNode n where exists ("
                    + "select 1 from ChatBranchSession s where s.agentId = n.agentId and s.sessionId = n.sessionId and s.updatedAt < :cutoff)")
    int deleteOfSessionsIdleBefore(@Param("cutoff") Instant cutoff);

    /**
     * Thread titles: the earliest USER node of each of the given session rows (by {@code
     * chat_branch_session.id}), one query for a whole page. Each row is {@code [agent_id (UUID),
     * session_id (String), content (String)]}. Ties on created_at (same-millisecond siblings) are
     * broken by sibling_index, so the original first message wins over a later edit of it.
     */
    @Query(
            value =
                    "SELECT DISTINCT ON (n.agent_id, n.session_id) n.agent_id, n.session_id, n.content"
                            + " FROM chat_branch_node n"
                            + " JOIN chat_branch_session s ON s.agent_id = n.agent_id AND s.session_id = n.session_id"
                            + " WHERE s.id IN (:sessionRowIds) AND n.role = 'USER'"
                            + " ORDER BY n.agent_id, n.session_id, n.created_at ASC, n.sibling_index ASC",
            nativeQuery = true)
    List<Object[]> findFirstUserMessageOfSessions(@Param("sessionRowIds") List<UUID> sessionRowIds);

    /** Thread delete: every node of one session. Run BEFORE its session row goes. */
    @Modifying
    @Query("delete from ChatBranchNode n where n.agentId = :agentId and n.sessionId = :sessionId")
    int deleteOfSession(@Param("agentId") UUID agentId, @Param("sessionId") String sessionId);

    /** Erasure: every node of every session the subject owns. Run BEFORE the session rows go. */
    @Modifying
    @Query(
            "delete from ChatBranchNode n where exists ("
                    + "select 1 from ChatBranchSession s where s.agentId = n.agentId and s.sessionId = n.sessionId and s.ownerSubject = :subject)")
    int deleteOfSessionsOwnedBy(@Param("subject") String subject);
}
