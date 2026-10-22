package com.agenticai.core.workflow;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.omnichannel.trigger.InboundTriggerContext;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;

/**
 * Background ("fire and poll") workflow runs - {@code POST /run} with {@code background: true}
 * lands here instead of the blocking {@link WorkflowService#run} path.
 *
 * <p><b>Why this exists (2026-08-17):</b> a workflow whose agent nodes think for minutes holds its
 * {@code POST /run} request open the whole time, and Cloudflare terminates any request older than
 * ~100s with a 524 - which not only shows the user a failure for a run that is still executing, but
 * (as the SDLC-demo incident showed) can interrupt mid-flight tool calls and trip the tool-failure
 * circuit breaker tenant-wide. The run row was ALWAYS pollable mid-run ({@code
 * WorkflowRunRequest#clientRunId}'s live-log-viewer design); the only missing piece was a way to
 * return from {@code POST /run} without waiting. This is that piece.
 *
 * <p><b>Split of work between threads:</b> {@link WorkflowExecutionEngine#prepareRun} (validate +
 * persist the RUNNING row + publish the started event) runs on the REQUEST thread - so a validation
 * failure is still an ordinary HTTP error with no row, and the id in the response is guaranteed
 * pollable before the caller can possibly ask for it. Only {@link
 * WorkflowExecutionEngine#continueRun} (the node traversal) moves to the worker pool. Cancellation
 * needs no thread affinity - Stop Run sets a DB flag the traversal polls between nodes - and crash
 * recovery is already owned by {@code WorkflowRunRecoveryJob}, which reaps RUNNING rows with stale
 * trace heartbeats no matter which thread was driving them.
 *
 * <p><b>Why a dedicated bounded pool</b> - the short version of {@code AgentJobExecutor}'s class
 * javadoc, which documents why {@code @Async} (unbounded virtual threads vs Hikari's 15), the
 * {@code @Scheduled} pool (size 8, shared, long tasks forbidden) and semaphore-plus-virtual-threads
 * (the bound IS the feature) are each wrong for minute-scale work that holds DB connections and LLM
 * slots. Unlike agent jobs there is no durable QUEUED state to fall back on - the run row is born
 * RUNNING - so a rejected submission must terminate the row honestly (FAILED, with a clear message)
 * rather than leave it RUNNING for the recovery job to reap with a misleading "instance stopped
 * mid-run" reason.
 */
@Service
public class WorkflowBackgroundRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowBackgroundRunService.class);

    /**
     * 4 concurrent background runs per replica, 16 queued. Sized against the same constraints as
     * {@code AgentJobExecutor}'s pool (Hikari 15, PgBouncer 25, shared hosted-LLM pool); queued
     * runs sit as RUNNING rows whose trace hasn't started yet, which the console renders as a run
     * that simply hasn't produced its first step.
     */
    private static final int MAX_CONCURRENT_RUNS = 4;

    private static final int MAX_QUEUED_RUNS = 16;

    private final WorkflowService workflowService;
    private final WorkflowExecutionEngine executionEngine;
    private final WorkflowRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final ThreadPoolExecutor pool;

    public WorkflowBackgroundRunService(
            WorkflowService workflowService,
            WorkflowExecutionEngine executionEngine,
            WorkflowRunRepository runRepository,
            ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.executionEngine = executionEngine;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("wf-bg-run-");
        threadFactory.setDaemon(true);
        this.pool =
                TenantAwareExecutors.newThreadPool(
                        MAX_CONCURRENT_RUNS,
                        MAX_CONCURRENT_RUNS,
                        60,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(MAX_QUEUED_RUNS),
                        threadFactory);
        this.pool.allowCoreThreadTimeOut(true);
    }

    /**
     * Validates, persists and returns the run in its initial RUNNING state, with the traversal
     * handed to the worker pool. The returned response's id is immediately pollable via {@code GET
     * /{workflowId}/runs/{runId}}.
     */
    public WorkflowRunResponse start(
            UUID workflowId,
            String input,
            String requestedBy,
            UUID clientRunId,
            List<ChatInlineAttachment> attachments) {
        return start(workflowId, input, requestedBy, clientRunId, attachments, RunMode.PRODUCTION);
    }

    /**
     * As above in an explicit {@link RunMode}: DRAFT runs the live editable graph and serves pins
     * (the canvas Run button with {@code background: true}); PRODUCTION is the snapshot and never
     * reads a pin.
     */
    public WorkflowRunResponse start(
            UUID workflowId,
            String input,
            String requestedBy,
            UUID clientRunId,
            List<ChatInlineAttachment> attachments,
            RunMode mode) {
        return start(workflowId, input, requestedBy, clientRunId, attachments, mode, null);
    }

    /**
     * As above, recording the run's optional operator comment on the row (not on the node input).
     */
    public WorkflowRunResponse start(
            UUID workflowId,
            String input,
            String requestedBy,
            UUID clientRunId,
            List<ChatInlineAttachment> attachments,
            RunMode mode,
            String comment) {
        RunMode effective = mode == null ? RunMode.PRODUCTION : mode;
        WorkflowDefinition definition = workflowService.get(workflowId);
        // C1: run the PRODUCTION-labeled snapshot's graph when one exists -
        // see WorkflowService#resolveRunGraph. The stamped version number
        // (prepareRun) resolves through the same rule, so the pin always
        // names the graph that executes.
        // A DRAFT run executes the live graph instead (see WorkflowService#run for the mode
        // contract).
        WorkflowGraphDto graph =
                effective.honoursPins()
                        ? workflowService.readGraph(definition.getGraphJson())
                        : workflowService.resolveRunGraph(definition);
        WorkflowRun run =
                comment == null
                        ? (effective == RunMode.PRODUCTION
                                ? executionEngine.prepareRun(
                                        definition, graph, clientRunId, requestedBy)
                                : executionEngine.prepareRun(
                                        definition, graph, clientRunId, requestedBy, effective))
                        : executionEngine.prepareRun(
                                definition, graph, clientRunId, requestedBy, effective, comment);

        // Same ThreadLocal snapshot/restore pair WorkflowExecutionEngine's
        // parallel fan-out already does - TenantContext for the schema, and
        // the inbound-trigger reply-to context in case a triggered path ever
        // opts into background mode.
        Optional<TenantContext> tenant = TenantContext.current();
        Optional<InboundTriggerContext> trigger = WorkflowTriggerContextHolder.current();
        try {
            pool.execute(
                    () -> {
                        tenant.ifPresent(TenantContext::set);
                        trigger.ifPresent(WorkflowTriggerContextHolder::set);
                        try {
                            executionEngine.continueRun(
                                    definition, graph, run, null, input, requestedBy, attachments);
                        } catch (RuntimeException e) {
                            failDefensively(run.getId(), e);
                        } finally {
                            // Unconditional - these are pooled threads, and a leaked
                            // TenantContext on a reused worker is a cross-tenant bug.
                            WorkflowTriggerContextHolder.clear();
                            TenantContext.clear();
                        }
                    });
        } catch (RejectedExecutionException e) {
            // No durable QUEUED state exists for workflow runs - terminate the
            // just-created row honestly instead of leaving it RUNNING forever.
            log.warn(
                    "Background run {} of workflow '{}' rejected - pool and queue are full.",
                    run.getId(),
                    definition.getName());
            run.complete(
                    WorkflowRunStatus.FAILED,
                    run.getTraceJson(),
                    "Too many workflow runs are executing right now - try again in a moment.");
            runRepository.save(run);
        }
        return WorkflowRunResponse.from(run, objectMapper);
    }

    /**
     * Resume a PAUSED run (V261) - the {@code POST .../runs/{runId}/resume} half of operator
     * pause/resume. The run's resume envelope (the node it was about to execute, and the input it
     * was about to receive, persisted by {@code WorkflowExecutionEngine#finalizePaused}) drives
     * {@link WorkflowExecutionEngine#resume} - the same re-entry primitive approval- resume uses,
     * which means a pause resume inherits its documented honest limits wholesale: in-process,
     * legacy engine, against the graph's CURRENT shape (a node deleted while paused fails the
     * resume).
     *
     * <p>The claim is the same guarded CAS ({@code PAUSED -> RUNNING}) the approval executor uses,
     * so a resume racing a cancel (or a second resume click) has exactly one winner. A non-PAUSED
     * run is a no-op returning the current row - same contract as {@code
     * WorkflowService#cancelRun}/{@code #pauseRun}, so a UI racing the run's own state never sees a
     * spurious failure.
     *
     * <p>Runs on this pool rather than the request thread for exactly the reason {@link #start}
     * does: the resumed leg can think for minutes.
     */
    public WorkflowRunResponse resumeRun(UUID workflowId, UUID runId) {
        // getRunEntity goes through workflowId+runId on purpose - the same
        // F3 "the path's workflowId is what requireAccess checked" rule as
        // every other request-path fetch.
        WorkflowRun run = workflowService.getRunEntity(workflowId, runId);
        WorkflowService.rejectTerminal(run, "resume");
        if (run.getStatus() != WorkflowRunStatus.PAUSED) {
            return WorkflowRunResponse.from(run, objectMapper);
        }
        int claimed =
                runRepository.compareAndSetStatus(
                        runId, WorkflowRunStatus.PAUSED, WorkflowRunStatus.RUNNING);
        if (claimed == 0) {
            WorkflowRun raced = runRepository.findById(runId).orElse(run);
            return WorkflowRunResponse.from(raced, objectMapper);
        }
        // Re-fetch after the CAS for the same reason the approval executor
        // does: the pre-claim entity may carry state a racing write changed.
        WorkflowRun claimedRun =
                runRepository
                        .findById(runId)
                        .orElseThrow(() -> new WorkflowRunNotFoundException(runId));
        String resumeNodeId = claimedRun.getPausedNodeId();
        String resumeNodeInput = claimedRun.getPausedNodeInput();
        if (resumeNodeId == null) {
            // A PAUSED row without an envelope can't re-enter the graph -
            // only reachable on rows written before V261 or a half-persisted
            // pause. Fail it honestly rather than strand it PAUSED forever.
            claimedRun.clearPauseEnvelope();
            claimedRun.complete(
                    WorkflowRunStatus.FAILED,
                    claimedRun.getTraceJson(),
                    "Cannot resume: this run was paused without a resume checkpoint.",
                    null,
                    null);
            runRepository.save(claimedRun);
            return WorkflowRunResponse.from(claimedRun, objectMapper);
        }
        // resume() rather than clearPauseEnvelope() alone: the CAS ran as a
        // bulk update, so this entity may still read PAUSED even though the
        // row is RUNNING - saving that stale stamp would undo the claim.
        claimedRun.resume();
        runRepository.save(claimedRun);

        WorkflowDefinition definition = workflowService.get(workflowId);
        // C1 pin: a resumed run keeps executing the version it started on -
        // re-resolving here would silently switch graphs mid-run whenever
        // PRODUCTION moved while the run was parked.
        WorkflowGraphDto graph =
                workflowService.readGraph(
                        workflowService.resolvePinnedGraphJson(
                                definition, claimedRun.getWorkflowVersion()));
        List<NodeTraceEntry> trace = readTraceTolerantly(claimedRun);

        Optional<TenantContext> tenant = TenantContext.current();
        try {
            pool.execute(
                    () -> {
                        tenant.ifPresent(TenantContext::set);
                        try {
                            // requestedBy: the run continues as its ORIGINAL
                            // requester, not the operator who resumed it - same
                            // attribution rule WorkflowApprovalActionExecutor's
                            // resumeRun documents.
                            executionEngine.resume(
                                    definition,
                                    graph,
                                    claimedRun,
                                    trace,
                                    resumeNodeId,
                                    resumeNodeInput,
                                    claimedRun.getRequestedBy());
                        } catch (RuntimeException e) {
                            failDefensively(claimedRun.getId(), e);
                        } finally {
                            TenantContext.clear();
                        }
                    });
        } catch (RejectedExecutionException e) {
            // The CAS already claimed the run as RUNNING - leaving it that
            // way would strand it until the recovery job reaps it hours
            // later. Best-effort terminalize with the real reason.
            log.warn("Resume of paused run {} rejected - pool and queue are full.", runId);
            failDefensively(runId, e);
        }
        return WorkflowRunResponse.from(claimedRun, objectMapper);
    }

    /**
     * Trace parse for resume - tolerant like {@code
     * WorkflowApprovalActionExecutor#readTraceTolerantly}: a corrupt trace resumes with the
     * recorded suffix empty rather than refusing the resume.
     */
    private List<NodeTraceEntry> readTraceTolerantly(WorkflowRun run) {
        try {
            String json = run.getTraceJson();
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new TypeReference<List<NodeTraceEntry>>() {});
        } catch (Exception e) {
            log.warn(
                    "Could not parse persisted trace for workflow run {} - resuming with no trace history.",
                    run.getId(),
                    e);
            return new ArrayList<>();
        }
    }

    /**
     * {@code runTraversal} terminates every failure it understands into the run row itself -
     * anything that escapes it would otherwise strand the row in RUNNING until {@code
     * WorkflowRunRecoveryJob} reaped it with a misleading reason. Reloads by id (an internal
     * write-back on a row this class just created - not a request-path read, so the F3 "always
     * fetch through workflowId" rule doesn't apply) to avoid clobbering any state the traversal
     * managed to persist before dying.
     */
    private void failDefensively(UUID runId, RuntimeException cause) {
        log.error("Background workflow run {} threw out of the execution engine.", runId, cause);
        try {
            runRepository
                    .findById(runId)
                    .ifPresent(
                            row -> {
                                if (row.getStatus() == WorkflowRunStatus.RUNNING) {
                                    row.complete(
                                            WorkflowRunStatus.FAILED,
                                            row.getTraceJson(),
                                            "Run failed unexpectedly: " + cause.getMessage());
                                    runRepository.save(row);
                                }
                            });
        } catch (RuntimeException persistFailure) {
            log.error(
                    "Could not record the failure of background run {} - the recovery job will reap it.",
                    runId,
                    persistFailure);
        }
    }

    @PreDestroy
    void shutdown() {
        // In-flight runs are NOT awaited - a deploy-time shutdown mid-run is
        // exactly the case WorkflowRunRecoveryJob exists for.
        pool.shutdownNow();
    }
}
