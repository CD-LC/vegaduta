package com.agenticai.core.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WorkflowWaitRepository extends JpaRepository<WorkflowWait, UUID> {

    /** The run's live wait, if any (at most one - see the partial unique index in V434). */
    Optional<WorkflowWait> findFirstByRunIdAndStatus(UUID runId, WorkflowWait.Status status);

    /** The run's most recent wait of any status (for the editor-facing description). */
    Optional<WorkflowWait> findFirstByRunIdOrderByCreatedAtDesc(UUID runId);

    /**
     * Waits in {@code status} whose wake time has passed, oldest first; bounded by {@code page} so one sweep
     * stays small. Callers pass {@link WorkflowWait.Status#WAITING}.
     */
    List<WorkflowWait> findByStatusAndWakeAtLessThanEqualOrderByWakeAtAsc(
        WorkflowWait.Status status, Instant now, Pageable page);

    /**
     * Atomic {@code expected} -> {@code to} claim: exactly one of two racing resolvers (the sweep and the resume
     * URL, or two sweeps on different replicas) sees {@code 1}; the loser sees {@code 0} and must leave the run
     * alone.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update WorkflowWait w set w.status = :to, w.resolvedAt = :now where w.id = :id and w.status = :expected")
    int claim(
        @Param("id") UUID id, @Param("expected") WorkflowWait.Status expected, @Param("to") WorkflowWait.Status to,
        @Param("now") Instant now);

    /** Retires any live wait of a run (defensive: a run is parked at one Wait node at a time). */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update WorkflowWait w set w.status = :to, w.resolvedAt = :now where w.runId = :runId and w.status = :live")
    int retireLiveForRun(
        @Param("runId") UUID runId, @Param("live") WorkflowWait.Status live, @Param("to") WorkflowWait.Status to,
        @Param("now") Instant now);
}
