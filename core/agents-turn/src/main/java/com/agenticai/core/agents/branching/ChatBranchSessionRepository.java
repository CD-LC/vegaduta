package com.agenticai.core.agents.branching;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@link ChatBranchSession}; used only through {@link JpaChatBranchStore}. */
public interface ChatBranchSessionRepository extends JpaRepository<ChatBranchSession, UUID> {

    Optional<ChatBranchSession> findByAgentIdAndSessionId(UUID agentId, String sessionId);

    @Modifying
    @Query("delete from ChatBranchSession s where s.updatedAt < :cutoff")
    int deleteIdleBefore(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from ChatBranchSession s where s.ownerSubject = :subject")
    int deleteOwnedBy(@Param("subject") String subject);
}
