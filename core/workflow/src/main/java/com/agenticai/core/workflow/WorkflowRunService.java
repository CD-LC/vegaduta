package com.agenticai.core.workflow;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.audit.KeysetCursor;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.agenticai.core.workflow.temporal.TemporalWorkflowExecutionEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Run / cancel / pause / list-runs, split out of {@link WorkflowService}. Every run path (human,
 * cron, webhook, channel, sub-workflow) goes through {@link #run}; see its javadoc for why it has no
 * access check.
 */
@Service
class WorkflowRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunService.class);

    private final WorkflowRunRepository runRepository;
    private final WorkflowExecutionEngine executionEngine;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<TemporalWorkflowExecutionEngine> temporalExecutionEngine;
    private final WorkflowRunCancellationService cancellationService;
    private final WorkflowRunPauseService pauseService;
    private final WorkflowAccessGuard guard;
    private final WorkflowGraphCodec codec;
    private final WorkflowVersionRestoreService versions;

    /**
     * Workflow engine selector. {@code legacy} (the default) runs every step
     * in-process, synchronously. {@code temporal} routes through {@link
     * TemporalWorkflowExecutionEngine} - same external contract, durable execution underneath. Only
     * takes effect if the "temporal" profile is also active; otherwise the run falls back to legacy.
     */
    @Value("${app.workflow.execution-engine:legacy}")
    private String executionEngineMode;

    WorkflowRunService(
            WorkflowRunRepository runRepository,
            WorkflowExecutionEngine executionEngine,
            ObjectMapper objectMapper,
            ObjectProvider<TemporalWorkflowExecutionEngine> temporalExecutionEngine,
            WorkflowRunCancellationService cancellationService,
            WorkflowRunPauseService pauseService,
            WorkflowAccessGuard guard,
            WorkflowGraphCodec codec,
            WorkflowVersionRestoreService versions) {
        this.runRepository = runRepository;
        this.executionEngine = executionEngine;
        this.objectMapper = objectMapper;
        this.temporalExecutionEngine = temporalExecutionEngine;
        this.cancellationService = cancellationService;
        this.pauseService = pauseService;
        this.guard = guard;
        this.codec = codec;
        this.versions = versions;
    }

    /**
     * Not {@code @Transactional}: {@link WorkflowExecutionEngine} makes an outbound LLM call
     * mid-execution (the Agent node) and persists its own run row as it goes - holding a single
     * long transaction across that call would be the exact anti-pattern {@code
     * HumanApprovalGateService}'s own javadoc already calls out for this codebase.
     *
     * <p>Cross-user resource sharing retrofit (Round 2) - deliberately NO {@link #requireAccess}
     * check anywhere in this method (or its overload below). This is the single shared execution
     * primitive for BOTH the human "Play" button ({@link WorkflowController#run}, which DOES
     * enforce RUNNER itself before ever calling this - see that method's own comment) and every
     * autonomous/system-triggered path that has no human actor to check against at all: {@link
     * #runScheduledWorkflow} (cron, via {@code WorkflowScheduler}), {@code
     * WorkflowWebhookController#trigger}/{@code #triggerOidc} (an external caller possessing a
     * shared-secret token or a service-account JWT with the {@code workflow-trigger} role - neither
     * is "a tenant member" in the resource-sharing sense), {@code WorkflowTriggerPortImpl} (inbound
     * WhatsApp/SMS/Voice channel messages, synthetic per-channel identity), and {@code
     * WorkflowNodeExecutor}'s "Trigger Workflow" node (one saved workflow calling another as a step
     * - the calling workflow's own author already chose the target from a picker at design time,
     * not a fresh cross-user access request). Routing any of these through a RUNNER check that
     * assumes a real, checkable tenant-member identity would either always deny (no identity to
     * check) or need its own bypass logic duplicated per caller - gating exclusively at the one
     * call site that's ACTUALLY human-initiated is simpler and correct: an autonomous trigger
     * firing is the workflow's own configured behavior running as designed, not a cross-user access
     * question.
     */
    public WorkflowRunResponse run(UUID id, String input, String requestedBy) {
        return run(id, null, input, requestedBy, null);
    }

    /**
     * @param clientRunId see {@code WorkflowRunRequest}'s javadoc (#393, real-time log viewer).
     */
    public WorkflowRunResponse run(UUID id, String input, String requestedBy, UUID clientRunId) {
        return run(id, null, input, requestedBy, clientRunId);
    }

    /**
     * Per-node workflow triggers - {@code startNodeId} overload. {@code null} keeps the
     * pre-per-node-trigger fallback (the manual "Run" button and the schedule trigger both still
     * call the no-{@code startNodeId} overloads above, which delegate here with {@code null}) of
     * starting from the first Trigger node found in the graph - see {@link
     * WorkflowExecutionEngine#execute}'s identical parameter for the full rationale.
     */
    public WorkflowRunResponse run(UUID id, String startNodeId, String input, String requestedBy) {
        return run(id, startNodeId, input, requestedBy, null);
    }

    /**
     * @param clientRunId see {@code WorkflowRunRequest}'s javadoc (#393, real-time log viewer).
     */
    public WorkflowRunResponse run(
            UUID id, String startNodeId, String input, String requestedBy, UUID clientRunId) {
        return run(id, startNodeId, input, requestedBy, clientRunId, List.of());
    }

    /**
     * Workflow Share mic/video: the fullest overload, carrying an optional inline image attachment
     * through to the engine - see {@code WorkflowRunRequest.attachments}'s javadoc for the full
     * contract (at most one node consumes it, and which one).
     */
    public WorkflowRunResponse run(
            UUID id,
            String startNodeId,
            String input,
            String requestedBy,
            UUID clientRunId,
            List<ChatInlineAttachment> attachments) {
        return run(
                id, startNodeId, input, requestedBy, clientRunId, attachments, RunMode.PRODUCTION);
    }

    /**
     * The mode-aware entry point. PRODUCTION (what every overload above passes, and so every
     * webhook, cron, channel, event, MCP, share-link, sub-workflow and agent-schedule run) executes
     * the PRODUCTION snapshot and never reads a pin. DRAFT - only the canvas Run button, via {@link
     * WorkflowController#run} - executes the LIVE editable graph (verified 2026-10-01: before this,
     * canvas Run ran the PRODUCTION snapshot, so a draft edit could not be run at all) and serves
     * pins; see {@link RunMode}.
     */
    public WorkflowRunResponse run(
            UUID id,
            String startNodeId,
            String input,
            String requestedBy,
            UUID clientRunId,
            List<ChatInlineAttachment> attachments,
            RunMode mode) {
        return run(id, startNodeId, input, requestedBy, clientRunId, attachments, mode, null);
    }

    /**
     * As above with the run's optional operator {@code comment}, stored on the run row and shown in
     * history. It is deliberately NOT merged into {@code input} (SE-14).
     */
    public WorkflowRunResponse run(
            UUID id,
            String startNodeId,
            String input,
            String requestedBy,
            UUID clientRunId,
            List<ChatInlineAttachment> attachments,
            RunMode mode,
            String comment) {
        RunMode effective = mode == null ? RunMode.PRODUCTION : mode;
        WorkflowDefinition definition = guard.get(id);
        WorkflowGraphDto graph =
                effective.honoursPins()
                        ? codec.readGraph(definition.getGraphJson())
                        : versions.resolveRunGraph(definition);
        WorkflowRun run =
                resolveExecutionEngine(
                        definition,
                        graph,
                        startNodeId,
                        input,
                        requestedBy,
                        clientRunId,
                        attachments,
                        effective,
                        comment);
        return WorkflowRunResponse.from(run, objectMapper);
    }

    /**
     * {@code temporalExecutionEngine.getIfAvailable()} is only ever non-null when the "temporal"
     * profile is active (see {@code TemporalConfig}) - so a deployment that sets {@code
     * app.workflow.execution-engine: temporal} but forgets to also start core with the "temporal"
     * profile degrades to legacy execution rather than throwing, logged once here rather than
     * silently.
     */
    private WorkflowRun resolveExecutionEngine(
            WorkflowDefinition definition,
            WorkflowGraphDto graph,
            String startNodeId,
            String input,
            String requestedBy,
            UUID clientRunId,
            List<ChatInlineAttachment> attachments,
            RunMode mode,
            String comment) {
        if ("temporal".equalsIgnoreCase(executionEngineMode)) {
            TemporalWorkflowExecutionEngine temporal = temporalExecutionEngine.getIfAvailable();
            if (temporal != null) {
                if (attachments != null && !attachments.isEmpty()) {
                    // Workflow Share mic/video: the Temporal-backed engine has
                    // its own serializable WorkflowRunInput with no attachment
                    // field yet - a separate, larger follow-up (durable-workflow
                    // payloads have different size/serialization constraints
                    // than a plain in-process call). Degrade with a log line
                    // rather than silently dropping with no trace, same
                    // "never silent" posture as every other fallback in this
                    // module.
                    log.warn(
                            "Workflow run for '{}' carries {} attachment(s), but the Temporal"
                                    + " execution engine does not yet support them - they will be"
                                    + " dropped for this run.",
                            definition.getName(),
                            attachments.size());
                }
                if (mode == RunMode.PRODUCTION && comment == null) {
                    return temporal.execute(
                            definition, graph, startNodeId, input, requestedBy, clientRunId);
                }
                return comment == null
                        ? temporal.execute(
                                definition,
                                graph,
                                startNodeId,
                                input,
                                requestedBy,
                                clientRunId,
                                mode)
                        : temporal.execute(
                                definition,
                                graph,
                                startNodeId,
                                input,
                                requestedBy,
                                clientRunId,
                                mode,
                                comment);
            }
            log.warn(
                    "app.workflow.execution-engine=temporal but the 'temporal' profile is not"
                        + " active - falling back to the legacy in-process engine for this run.");
        }
        if (mode == RunMode.PRODUCTION && comment == null) {
            return executionEngine.execute(
                    definition, graph, startNodeId, input, requestedBy, clientRunId, attachments);
        }
        return comment == null
                ? executionEngine.execute(
                        definition,
                        graph,
                        startNodeId,
                        input,
                        requestedBy,
                        clientRunId,
                        attachments,
                        mode)
                : executionEngine.execute(
                        definition,
                        graph,
                        startNodeId,
                        input,
                        requestedBy,
                        clientRunId,
                        attachments,
                        mode,
                        comment);
    }

    @Transactional(readOnly = true)
    public List<WorkflowRunResponse> listRuns(UUID workflowId) {
        // UNBOUNDED by design for internal callers; the HTTP list uses the capped overload below.
        return runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId).stream()
                .map(r -> WorkflowRunResponse.from(r, objectMapper))
                .toList();
    }

    /** Real run total for the workflow (X-Total-Count on the capped list). */
    @Transactional(readOnly = true)
    public long countRuns(UUID workflowId) {
        return runRepository.countByWorkflowId(workflowId);
    }

    /** Default / hard-max row counts for the run-history list (each row carries its trace). */
    static final int RUNS_DEFAULT_LIMIT = 200;

    static final int RUNS_MAX_LIMIT = 1000;

    /**
     * Newest {@code limit} runs (null = default 200, clamped to [1, 1000]), same order and shape as
     * before.
     */
    @Transactional(readOnly = true)
    public List<WorkflowRunResponse> listRuns(UUID workflowId, Integer limit) {
        return listRuns(workflowId, limit, null);
    }

    /**
     * As above, skipping the newest {@code offset} runs (null/negative = 0) to reach older history.
     */
    @Transactional(readOnly = true)
    public List<WorkflowRunResponse> listRuns(UUID workflowId, Integer limit, Long offset) {
        var page = ListPaging.page(limit, offset, RUNS_DEFAULT_LIMIT, RUNS_MAX_LIMIT);
        return runRepository.findByWorkflowIdOrderByStartedAtDescIdDesc(workflowId, page).stream()
                .map(r -> WorkflowRunResponse.from(r, objectMapper))
                .toList();
    }

    /**
     * Keyset page of run history after {@code after}: at most {@code limit + 1} rows (limit clamped
     * like the offset page), so the caller can tell whether another page exists without a count.
     */
    @Transactional(readOnly = true)
    public List<WorkflowRunResponse> listRunsAfter(UUID workflowId, KeysetCursor after, int limit) {
        return runRepository
                .findRunsAfter(
                        workflowId,
                        after.timestamp(),
                        after.id(),
                        ListPaging.page(limit + 1, 0L, RUNS_DEFAULT_LIMIT, RUNS_MAX_LIMIT + 1))
                .stream()
                .map(r -> WorkflowRunResponse.from(r, objectMapper))
                .toList();
    }

    /**
     * Security audit 2026-07-29 (F3) - takes {@code workflowId} as well as {@code runId} and loads
     * through {@link WorkflowRunRepository#findByIdAndWorkflowId} so the run is structurally bound
     * to the workflow the caller was actually access-checked against in {@link
     * WorkflowController#getRun}. Previously this took {@code runId} alone and used a bare {@code
     * findById}, which meant the check ran on the caller's own workflow while the response could
     * carry a DIFFERENT user's run - trace included (the per-node inputs/outputs of whatever that
     * workflow processed). 404, never 403, on mismatch - see the repository method's javadoc.
     */
    @Transactional(readOnly = true)
    public WorkflowRunResponse getRun(UUID workflowId, UUID runId) {
        return WorkflowRunResponse.from(requireRunOfWorkflow(workflowId, runId), objectMapper);
    }

    /**
     * Security audit 2026-07-29 (F3) - the single loader for "one run, on behalf of a request".
     * Nothing on the request path may call {@code runRepository.findById(runId)} directly; doing so
     * re-opens the IDOR this replaces (the path's {@code workflowId} is what {@link #requireAccess}
     * checked, so it must also be what the run is fetched by).
     */
    private WorkflowRun requireRunOfWorkflow(UUID workflowId, UUID runId) {
        return runRepository
                .findByIdAndWorkflowId(runId, workflowId)
                .orElseThrow(() -> new WorkflowRunNotFoundException(runId));
    }

    /**
     * Stop Run - like {@link #run}, deliberately NOT {@code @Transactional}: this makes a Temporal
     * client call ({@link TemporalWorkflowExecutionEngine#cancelRun}) that shouldn't sit inside a
     * held DB transaction, same reasoning as that method's own javadoc.
     *
     * <p>Only a genuinely RUNNING run is cancellable - COMPLETED/FAILED/ CANCELLED have nothing
     * left to stop, and AWAITING_APPROVAL has no in-flight execution to interrupt (the run is
     * parked; since approval- resume landed the way to stop one is REJECTING its pending approval
     * request, which marks the run FAILED - see {@code WorkflowApprovalActionExecutor#onRejected} -
     * and a run RESUMED from approval is RUNNING again and cancellable here like any other). All
     * four read as a no-op - this returns the run's current, unchanged status rather than an error.
     * The exception (SE-54) is a run that already finished (COMPLETED, FAILED, CANCELLED): that is a
     * 409 naming its status, so a caller is not told 200 for a stop that stopped nothing.
     *
     * <p>This signals BOTH mechanisms unconditionally rather than trying to determine which engine
     * actually executed this run (that isn't recorded per-row anywhere, and {@code
     * app.workflow.execution-engine} could in principle change between when a run started and when
     * it's cancelled): the Redis flag {@link WorkflowExecutionEngine#execute} polls, and - if the
     * "temporal" profile is active - a real Temporal cancel request via {@link
     * TemporalWorkflowExecutionEngine#cancelRun}. Whichever one actually ran this row acts on its
     * own signal; the other is a harmless no-op (a Redis key nothing ever reads, or a cancel
     * request against a Temporal workflow id that was never started).
     *
     * <p>The returned status may still read RUNNING immediately after this call - cancellation is a
     * request, not a wait-for-completion operation (this method returns as soon as the signal is
     * sent, not once the run actually stops) - the canvas's existing run-status poll picks up the
     * transition to CANCELLED on its next tick, same as it already does for every other status
     * change.
     *
     * <p>Security audit 2026-07-29 (F3) - takes {@code workflowId} too, for the same reason {@link
     * #getRun} does: without it, the RUNNER check in {@link WorkflowController#cancelRun} ran
     * against the caller's own workflow while the cancellation signal went to ANY run in the
     * tenant.
     */
    public WorkflowRunResponse cancelRun(UUID workflowId, UUID runId) {
        WorkflowRun run = requireRunOfWorkflow(workflowId, runId);
        if (run.getStatus() == WorkflowRunStatus.PAUSED) {
            // A paused run has no in-flight execution to signal - the node it
            // was about to run never started, so cancelling is a plain status
            // transition, guarded by the same CAS the resume path uses so a
            // cancel racing a resume has exactly one winner.
            int claimed =
                    runRepository.compareAndSetStatus(
                            runId, WorkflowRunStatus.PAUSED, WorkflowRunStatus.CANCELLED);
            if (claimed == 0) {
                WorkflowRun raced =
                        runRepository.findByIdAndWorkflowId(runId, workflowId).orElse(run);
                return WorkflowRunResponse.from(raced, objectMapper);
            }
            run.clearPauseEnvelope();
            run.complete(
                    WorkflowRunStatus.CANCELLED,
                    run.getTraceJson(),
                    "Cancelled by user request while paused.",
                    null,
                    null);
            runRepository.save(run);
            pauseService.clearPauseFlag(runId);
            return WorkflowRunResponse.from(run, objectMapper);
        }
        rejectTerminal(run, "cancel");
        if (run.getStatus() != WorkflowRunStatus.RUNNING) {
            return WorkflowRunResponse.from(run, objectMapper);
        }
        cancellationService.requestCancellation(runId);
        TemporalWorkflowExecutionEngine temporal = temporalExecutionEngine.getIfAvailable();
        if (temporal != null) {
            temporal.cancelRun(runId);
        }
        WorkflowRun refreshed = runRepository.findByIdAndWorkflowId(runId, workflowId).orElse(run);
        // SE-54: the row still reads RUNNING (cancellation is observed between nodes); say the stop was
        // accepted instead of returning what looks like a no-op.
        return refreshed.getStatus() == WorkflowRunStatus.RUNNING
                ? WorkflowRunResponse.cancelRequested(refreshed, objectMapper)
                : WorkflowRunResponse.from(refreshed, objectMapper);
    }

    /**
     * SE-54: cancel, pause and resume on a run that already finished answer 409 naming its current
     * status, instead of 200 with an unchanged body that reads like success.
     */
    static void rejectTerminal(WorkflowRun run, String action) {
        WorkflowRunStatus status = run.getStatus();
        if (status == WorkflowRunStatus.COMPLETED
                || status == WorkflowRunStatus.FAILED
                || status == WorkflowRunStatus.CANCELLED) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot " + action + " run " + run.getId() + ": it is already " + status + ".");
        }
    }

    /**
     * Pause Run (V261) - asks a RUNNING run to park itself at the next node boundary. Same
     * request-not-wait contract as {@link #cancelRun}: this writes the Redis flag {@link
     * WorkflowExecutionEngine} polls at its between-node checkpoint and returns the run's current
     * status - still RUNNING until the traversal actually observes the flag and flips the row to
     * PAUSED with its resume envelope. A node already mid-flight is allowed to finish; a run in any
     * other state is a no-op returning the current row, same as cancel.
     *
     * <p>Legacy engine only - a Temporal-path run never polls the flag and simply keeps executing
     * (the flag expires on its own), the identical honest no-op {@link WorkflowRunPauseService}'s
     * javadoc documents.
     */
    public WorkflowRunResponse pauseRun(UUID workflowId, UUID runId) {
        WorkflowRun run = requireRunOfWorkflow(workflowId, runId);
        rejectTerminal(run, "pause");
        if (run.getStatus() != WorkflowRunStatus.RUNNING) {
            return WorkflowRunResponse.from(run, objectMapper);
        }
        pauseService.requestPause(runId);
        WorkflowRun refreshed = runRepository.findByIdAndWorkflowId(runId, workflowId).orElse(run);
        return WorkflowRunResponse.from(refreshed, objectMapper);
    }

    /**
     * Output node "file" destination (#152) - the raw entity, not the DTO, since the controller
     * needs {@code outputData} verbatim to stream back as a download and {@link
     * WorkflowRunResponse} deliberately never exposes the full body (only the file name, so the
     * canvas can show a "Download output.txt" link without fetching potentially large content on
     * every run-list poll).
     *
     * <p>Security audit 2026-07-29 (F3) - takes {@code workflowId} too. This one streamed the raw
     * {@code outputData} of ANY run in the tenant when it was keyed on {@code runId} alone, which
     * made it the highest-value leg of the three (a whole output file, not just a trace).
     */
    @Transactional(readOnly = true)
    public WorkflowRun getRunEntity(UUID workflowId, UUID runId) {
        return requireRunOfWorkflow(workflowId, runId);
    }
}
