package com.agenticai.core.agents.branching;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@link ChatBranchSession}; used only through {@link JpaChatBranchStore}. */
public interface ChatBranchSessionRepository extends JpaRepository<ChatBranchSession, UUID> {

    Optional<ChatBranchSession> findByAgentIdAndSessionId(UUID agentId, String sessionId);

    /** Thread list, first page: the subject's sessions, newest activity first (idx_chat_branch_session_owner, V661). */
    List<ChatBranchSession> findByOwnerSubjectOrderByUpdatedAtDesc(String ownerSubject, Pageable page);

    /** Thread list, later pages: the same list, continued strictly before the cursor. */
    List<ChatBranchSession> findByOwnerSubjectAndUpdatedAtBeforeOrderByUpdatedAtDesc(
        String ownerSubject, Instant before, Pageable page);

    @Modifying
    @Query("delete from ChatBranchSession s where s.updatedAt < :cutoff")
    int deleteIdleBefore(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from ChatBranchSession s where s.ownerSubject = :subject")
    int deleteOwnedBy(@Param("subject") String subject);
}
