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

    List<ChatBranchNode> findByAgentIdAndSessionIdOrderByCreatedAtAsc(UUID agentId, String sessionId);

    /** Retention: every node of every session whose branching state was last touched before {@code cutoff}. Run BEFORE the session rows go. */
    @Modifying
    @Query("delete from ChatBranchNode n where exists ("
        + "select 1 from ChatBranchSession s where s.agentId = n.agentId and s.sessionId = n.sessionId and s.updatedAt < :cutoff)")
    int deleteOfSessionsIdleBefore(@Param("cutoff") Instant cutoff);

    /** Erasure: every node of every session the subject owns. Run BEFORE the session rows go. */
    @Modifying
    @Query("delete from ChatBranchNode n where exists ("
        + "select 1 from ChatBranchSession s where s.agentId = n.agentId and s.sessionId = n.sessionId and s.ownerSubject = :subject)")
    int deleteOfSessionsOwnedBy(@Param("subject") String subject);
}
