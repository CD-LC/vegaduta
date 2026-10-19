package com.agenticai.core.quality.dataset;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Tenant-scoped repository for {@link EvalRun}. */
@Repository
public interface EvalRunRepository extends JpaRepository<EvalRun, UUID> {

    List<EvalRun> findByDatasetIdOrderByCreatedAtDesc(UUID datasetId, Pageable pageable);

    /**
     * An agent's most recent run in {@code status} across all its datasets -
     * the workflow canvas's per-node eval badge (core/workflow
     * {@code WorkflowInsightsService}) shows the latest finished score.
     */
    Optional<EvalRun> findFirstByAgentIdAndStatusOrderByCreatedAtDesc(UUID agentId, EvalRunStatus status);

    /**
     * The baseline for diff-vs-previous: the most recent SUCCEEDED run of
     * the same dataset created before {@code before} that actually measured
     * something ({@code overallScore} not null). An INCONCLUSIVE or
     * unmeasured run is "nothing to compare against", never a baseline -
     * the {@code TokenBaseline} rule applied to scores. Derived query
     * (rather than JPQL with a LIMIT clause) so it stays portable across
     * the Hibernate versions this repo has moved through.
     */
    Optional<EvalRun> findFirstByDatasetIdAndStatusAndOverallScoreIsNotNullAndCreatedAtBeforeOrderByCreatedAtDesc(
        UUID datasetId, EvalRunStatus status, java.time.Instant before);

    boolean existsByDatasetIdAndStatusIn(UUID datasetId, List<EvalRunStatus> statuses);

    /**
     * Idempotent-trigger lookup (V343): at most one run per
     * (dataset_id, idempotency_key), enforced by {@code
     * uq_eval_run_idempotency_key}. A retried trigger with the same key
     * returns this row instead of creating a second run or a 409.
     */
    Optional<EvalRun> findByDatasetIdAndIdempotencyKey(UUID datasetId, String idempotencyKey);

    /**
     * Boot orphan sweep (see {@code EvalRunExecutor}): runs execute on an
     * in-process executor that dies with the JVM, so any non-terminal row
     * found at startup is unowned by construction. A guarded bulk UPDATE
     * rather than load-modify-save so the sweep is one statement per tenant
     * schema and cannot race a (nonexistent, but future) concurrent writer
     * into a lost update. {@code @Transactional} sits ON the repository
     * method because the caller is a {@code TenantSchemaIterator} lambda
     * inside an {@code @EventListener} - no surrounding transaction exists
     * there, and a {@code @Modifying} update without one throws
     * {@code TransactionRequiredException}.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("""
        UPDATE EvalRun r
        SET r.status = com.agenticai.core.quality.dataset.EvalRunStatus.FAILED,
            r.errorMessage = :reason,
            r.finishedAt = :now
        WHERE r.status IN (com.agenticai.core.quality.dataset.EvalRunStatus.QUEUED,
                           com.agenticai.core.quality.dataset.EvalRunStatus.RUNNING)
        """)
    int failAllLiveRuns(@Param("reason") String reason, @Param("now") java.time.Instant now);

    /**
     * As {@link #failAllLiveRuns} but only rows created before {@code cutoff}: the multi-replica-safe sweep
     * ({@code app.quality.boot-orphan-sweep=stale}) that must not fail a run another replica started
     * minutes ago. Same transactional reasoning as above.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("""
        UPDATE EvalRun r
        SET r.status = com.agenticai.core.quality.dataset.EvalRunStatus.FAILED,
            r.errorMessage = :reason,
            r.finishedAt = :now
        WHERE r.status IN (com.agenticai.core.quality.dataset.EvalRunStatus.QUEUED,
                           com.agenticai.core.quality.dataset.EvalRunStatus.RUNNING)
          AND r.createdAt < :cutoff
        """)
    int failStaleLiveRuns(@Param("reason") String reason, @Param("now") java.time.Instant now,
                          @Param("cutoff") java.time.Instant cutoff);
}
