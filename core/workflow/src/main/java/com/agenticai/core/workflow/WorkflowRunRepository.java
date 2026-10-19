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

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    List<WorkflowRun> findByWorkflowIdOrderByStartedAtDesc(UUID workflowId);

    long countByWorkflowId(UUID workflowId);

    /** Newest-first run history bounded by {@code page} (the list endpoint's LIMIT). */
    List<WorkflowRun> findByWorkflowIdOrderByStartedAtDescIdDesc(UUID workflowId, Pageable page);

    /**
     * Keyset page of run history: runs strictly older than ({@code startedAt}, {@code id}) in the
     * same (startedAt DESC, id DESC) order as {@link #findByWorkflowIdOrderByStartedAtDescIdDesc},
     * bounded by {@code page}. Backs {@code ?cursor=} on the runs list (see KeysetCursor).
     */
    @Query(
            "select r from WorkflowRun r where r.workflowId = :workflowId"
                    + " and (r.startedAt < :startedAt or (r.startedAt = :startedAt and r.id < :id))"
                    + " order by r.startedAt desc, r.id desc")
    List<WorkflowRun> findRunsAfter(
            @Param("workflowId") UUID workflowId,
            @Param("startedAt") Instant startedAt,
            @Param("id") UUID id,
            Pageable page);

    /**
     * The newest run of a workflow, any mode: the source of the "last run" data for expression
     * previews.
     */
    Optional<WorkflowRun> findFirstByWorkflowIdOrderByStartedAtDesc(UUID workflowId);

    /**
     * Canvas insights (eval badges, lint cost/latency hints): the newest runs inside a window,
     * bounded by {@code page} so a busy workflow never loads its whole history (each row carries
     * its trace TEXT).
     */
    List<WorkflowRun> findByWorkflowIdAndStartedAtAfterOrderByStartedAtDesc(
            UUID workflowId, Instant since, Pageable page);

    /**
     * Crash recovery ({@link WorkflowRunRecoveryJob}): the synchronous default engine persists
     * every run as RUNNING up front and only flips it to a terminal status from the same JVM that
     * started it, so an instance that dies mid-run leaves the row RUNNING forever - invisible to
     * every "is it done yet" poll and never reaped by anything, until this finder existed.
     *
     * <p>Candidates are selected on {@code startedAt} (the only wall-clock column the row has
     * besides {@code completedAt}) rather than a dedicated heartbeat column - deliberately no
     * schema migration; see {@link WorkflowRunRecoveryJob}'s javadoc for why, and for how the job
     * refines these candidates against the trace's own per-node timestamps (which the engine
     * already persists after every node) before declaring one stale.
     */
    List<WorkflowRun> findByStatusAndStartedAtBefore(WorkflowRunStatus status, Instant cutoff);

    /**
     * Security audit 2026-07-29 (F3) - the ONLY way {@link WorkflowService} should ever load a
     * single run on behalf of a request, deliberately replacing bare {@code findById(runId)}
     * everywhere on that path.
     *
     * <p>Every per-run endpoint on {@code WorkflowController} is shaped {@code
     * /api/workflows/{workflowId}/runs/{runId}} and enforces access against the PATH's {@code
     * workflowId} via {@code WorkflowService#requireAccess}. With {@code findById(runId)} the two
     * ids were never tied together, so a caller could pass a workflow it legitimately owns plus
     * somebody else's {@code runId}: the access check passed against its own workflow while the
     * response carried the other user's run - including {@code WorkflowRunResponse.trace} (the
     * per-node inputs and outputs, i.e. the actual data that workflow processed), the {@code
     * /download} endpoint's output file, and {@code /cancel}'s ability to kill any run in the
     * tenant. Making the binding a repository query rather than a hand-written {@code if} means a
     * future per-run endpoint cannot forget it - there is no unscoped single-run finder to reach
     * for.
     *
     * <p>Empty (rather than a distinct "wrong workflow" signal) on mismatch on purpose: callers
     * turn it into the same 404 a genuinely unknown run id produces, so probing run ids leaks
     * nothing - same anti-enumeration convention {@code ResourceAccessService#requireAccess}
     * follows.
     */
    Optional<WorkflowRun> findByIdAndWorkflowId(UUID id, UUID workflowId);

    /**
     * Approval-node resume (F-05, second half) - the double-resume guard's chosen mechanism: an
     * atomic, guarded single-statement UPDATE (compare -and-set on {@code status}) rather than an
     * optimistic load-check-save, because {@link WorkflowRun} has no {@code @Version} column and
     * adding one would be a schema migration this feature deliberately avoids. Two concurrent
     * decisions on the same AWAITING_APPROVAL run (two approval requests for one run, or an admin
     * decision racing {@code RetryFailedActionsJob}) both issue this statement; the database
     * serializes them and exactly one sees {@code 1} updated row - the loser observes {@code 0} and
     * must treat the resume as already claimed.
     *
     * <p>{@code errorMessage}/{@code completedAt} are cleared in the same statement because the
     * halt path persisted them ("Awaiting human approval...", halt time) via {@link
     * WorkflowRun#complete} - a resumed run is genuinely in-flight again and must not look
     * finished. The caller writes the real terminal values later through {@code complete}.
     *
     * <p>{@code @Transactional} here (unusual on a repository, deliberate): {@code @Modifying} JPQL
     * needs a transaction, and the callers ({@code WorkflowApprovalActionExecutor}) run outside one
     * by design - {@code HumanApprovalGateService} keeps executor invocations un-transactional so
     * slow node executions never hold a DB connection. Scoping the transaction to this single
     * statement preserves that. {@code clearAutomatically} so a subsequent {@code findById} in the
     * same thread sees the new status, not a stale first-level-cache copy.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            "update WorkflowRun r set r.status = :to, r.errorMessage = null, r.completedAt = null "
                    + "where r.id = :id and r.status = :expected")
    int compareAndSetStatus(
            @Param("id") UUID id,
            @Param("expected") WorkflowRunStatus expected,
            @Param("to") WorkflowRunStatus to);

    /**
     * Wait node wake-up: rewrites a PARKED run's trace and the input it will resume with, in one
     * guarded statement. A targeted update rather than {@code save(entity)} on purpose - saving a
     * whole entity read a moment earlier could write a stale status back over a concurrent operator
     * cancel.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(
            "update WorkflowRun r set r.traceJson = :trace, r.pausedNodeInput = :input "
                    + "where r.id = :id and r.status = :expected")
    int updateParkedCheckpoint(
            @Param("id") UUID id,
            @Param("expected") WorkflowRunStatus expected,
            @Param("trace") String trace,
            @Param("input") String input);
}
