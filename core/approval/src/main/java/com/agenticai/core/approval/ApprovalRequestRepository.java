package com.agenticai.core.approval;

import java.time.Instant;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {

    List<ApprovalRequest> findByStatusOrderByCreatedAtDesc(ApprovalStatus status);

    List<ApprovalRequest> findAllByOrderByCreatedAtDesc();

    List<ApprovalRequest> findByStatusOrderByCreatedAtDescIdDesc(
            ApprovalStatus status, Pageable page);

    /**
     * The row under a write lock ({@code SELECT ... FOR UPDATE}), for the approve/reject decision.
     * Two deciders racing on one PENDING request would otherwise both read PENDING, both pass the
     * check and both run the approved action; with the lock the second waits, then sees the
     * decided status and is refused. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ApprovalRequest r where r.id = :id")
    Optional<ApprovalRequest> findByIdForUpdate(@Param("id") UUID id);

    long countByStatus(ApprovalStatus status);

    /**
     * The requests one conversation's tool guard recorded ({@code requestedBy} is the {@code
     * "agent:<agentId>:session:<sessionId>"} label) in one state, oldest first. Read when a human
     * decides one of them, to tell the continued task which of its other actions still wait.
     */
    List<ApprovalRequest> findByRequestedByAndStatusOrderByCreatedAtAsc(
            String requestedBy, ApprovalStatus status);

    /**
     * What one person decided since {@code after}, newest first, at most 500 rows. Read by the
     * preferences-from-behavior loop, which only counts; it never changes an approval.
     */
    List<ApprovalRequest> findTop500ByDecidedByAndDecidedAtAfterOrderByDecidedAtDesc(
            String decidedBy, Instant after);

    List<ApprovalRequest> findAllByOrderByCreatedAtDescIdDesc(Pageable page);

    /**
     * {@code ApprovalExpiryJob}'s query: the oldest requests in {@code status} created before {@code
     * cutoff}, one page of them.
     */
    List<ApprovalRequest> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            ApprovalStatus status, Instant cutoff, Pageable page);

    /**
     * Autonomy/Guardrails Strategy Stage C - {@code RetryFailedActionsJob}'s due-for-retry query.
     * {@code nextRetryAt} is null for a permanently (non-retryable) failed request even while
     * {@code status = EXECUTION_FAILED}, so this only ever matches genuinely retry-scheduled rows -
     * see {@code ApprovalStatus}'s javadoc.
     */
    List<ApprovalRequest> findByStatusAndNextRetryAtBefore(ApprovalStatus status, Instant cutoff);
}
