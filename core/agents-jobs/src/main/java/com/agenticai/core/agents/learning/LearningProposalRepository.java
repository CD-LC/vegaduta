package com.agenticai.core.agents.learning;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for {@link LearningProposal}. Every read is scoped to one subject. */
public interface LearningProposalRepository extends JpaRepository<LearningProposal, UUID> {

    Optional<LearningProposal> findByIdAndSubject(UUID id, String subject);

    List<LearningProposal> findTop200BySubjectOrderByCreatedAtDesc(String subject);

    List<LearningProposal> findTop200BySubjectAndStatusOrderByCreatedAtDesc(
            String subject, LearningProposalStatus status);

    List<LearningProposal> findTop200BySubjectAndKindOrderByCreatedAtDesc(
            String subject, LearningProposalKind kind);

    List<LearningProposal> findTop200BySubjectAndStatusAndKindOrderByCreatedAtDesc(
            String subject, LearningProposalStatus status, LearningProposalKind kind);

    long countBySubjectAndStatus(String subject, LearningProposalStatus status);

    boolean existsBySubjectAndKindAndBodyHashAndStatusIn(
            String subject,
            LearningProposalKind kind,
            String bodyHash,
            Collection<LearningProposalStatus> statuses);

    /** Pending proposals older than {@code cutoff} become EXPIRED. Returns the rows changed. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            "update LearningProposal p set p.status = :expired, p.decidedAt = :now "
                    + "where p.status = :pending and p.createdAt < :cutoff")
    int expirePendingBefore(
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            @Param("pending") LearningProposalStatus pending,
            @Param("expired") LearningProposalStatus expired);
}
