package com.agenticai.core.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic statements over {@code workflow_effect_journal} (V680). Every write runs in its OWN
 * transaction ({@code REQUIRES_NEW}): a STARTED row must be durable before the side effect fires,
 * whatever transaction the caller happens to be in, and it must survive that transaction rolling
 * back.
 */
public interface WorkflowEffectJournalRepository
        extends JpaRepository<WorkflowEffectJournalEntry, UUID> {

    Optional<WorkflowEffectJournalEntry> findByRunIdAndNodeIdAndIterationKey(
            UUID runId, String nodeId, String iterationKey);

    List<WorkflowEffectJournalEntry> findByRunIdOrderByStartedAtAsc(UUID runId);

    List<WorkflowEffectJournalEntry> findByRunIdAndStatusInOrderByStartedAtAsc(
            UUID runId, List<String> statuses);

    /** Claims the key; 1 = this caller owns the effect, 0 = a row already exists. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(
            value =
                    "insert into workflow_effect_journal (id, run_id, node_id, node_type,"
                            + " iteration_key, idempotency_key, status, attempt_count, started_at)"
                            + " values (gen_random_uuid(), :runId, :nodeId, :nodeType,"
                            + " :iteration, :idemKey, 'STARTED', 1, now())"
                            + " on conflict (run_id, node_id, iteration_key) do nothing",
            nativeQuery = true)
    int claim(
            @Param("runId") UUID runId,
            @Param("nodeId") String nodeId,
            @Param("nodeType") String nodeType,
            @Param("iteration") String iteration,
            @Param("idemKey") String idemKey);

    /** Takes over a row in one of {@code fromStatuses}; 1 = taken, 0 = somebody else moved it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(
            value =
                    "update workflow_effect_journal set status = 'STARTED',"
                            + " attempt_count = attempt_count + 1, started_at = now(),"
                            + " completed_at = null where run_id = :runId and node_id = :nodeId"
                            + " and iteration_key = :iteration and status in (:fromStatuses)",
            nativeQuery = true)
    int takeOver(
            @Param("runId") UUID runId,
            @Param("nodeId") String nodeId,
            @Param("iteration") String iteration,
            @Param("fromStatuses") List<String> fromStatuses);

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(
            value =
                    "update workflow_effect_journal set status = 'COMPLETED', completed_at = now(),"
                            + " result_status = :resultStatus, result_output = :output,"
                            + " result_output_truncated = :truncated, result_digest = :digest,"
                            + " result_branch = :branch, result_summary = :summary,"
                            + " result_file_name = :fileName where run_id = :runId"
                            + " and node_id = :nodeId and iteration_key = :iteration"
                            + " and status in ('STARTED', 'UNCERTAIN')",
            nativeQuery = true)
    int complete(
            @Param("runId") UUID runId,
            @Param("nodeId") String nodeId,
            @Param("iteration") String iteration,
            @Param("resultStatus") String resultStatus,
            @Param("output") String output,
            @Param("truncated") boolean truncated,
            @Param("digest") String digest,
            @Param("branch") String branch,
            @Param("summary") String summary,
            @Param("fileName") String fileName);

    /** Moves a STARTED/UNCERTAIN row to {@code toStatus} (FAILED or UNCERTAIN). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(
            value =
                    "update workflow_effect_journal set status = :toStatus where run_id = :runId"
                            + " and node_id = :nodeId and iteration_key = :iteration"
                            + " and status in ('STARTED', 'UNCERTAIN')",
            nativeQuery = true)
    int transition(
            @Param("runId") UUID runId,
            @Param("nodeId") String nodeId,
            @Param("iteration") String iteration,
            @Param("toStatus") String toStatus);

    /** Marks every still-STARTED row of a (dead) run UNCERTAIN; returns how many. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(
            value =
                    "update workflow_effect_journal set status = 'UNCERTAIN'"
                            + " where run_id = :runId and status = 'STARTED'",
            nativeQuery = true)
    int markStartedUncertain(@Param("runId") UUID runId);

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(
            value = "delete from workflow_effect_journal where started_at < :cutoff",
            nativeQuery = true)
    int purgeStartedBefore(@Param("cutoff") Instant cutoff);
}
