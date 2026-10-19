package com.agenticai.core.workflow;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.concurrent.PlatformExecutorConfiguration;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.stream.WorkflowRunStreamHub;
import com.agenticai.core.workflow.version.WorkflowVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Interactive Workflow Designer PRD, Section 6: "an orchestration layer over
 * current services, not a rewrite of any of them." Walks a validated graph
 * node by node, delegating each node's actual execution to {@link
 * WorkflowNodeExecutor} (the per-node logic was extracted
 * out of this class so it could be shared with the new
 * Temporal-backed path - see that class's javadoc).
 *
 * <p><b>Execution model note</b>: this remains the default, synchronous,
 * in-process execution path - the canvas's own "Run" button and the REST
 * {@code POST /api/workflows/{id}/run} endpoint both still go through this
 * class unless {@code app.workflow.execution-engine} is set to {@code
 * temporal} (see {@code com.agenticai.core.workflow.temporal.TemporalWorkflowExecutionEngine}),
 * in which case {@link WorkflowService} delegates to that class instead -
 * same external behavior (synchronous response with the full trace), durable
 * crash-resumable execution underneath. This class's own PRD Section 7/9
 * target (a Kafka-consumer-driven engine for real channel triggers) remains
 * real, un-started scope independent of the Temporal work.
 */
@Service
public class WorkflowExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionEngine.class);

    // Parallel Fan-Out (Master Roadmap 2026 item #12, §E) - defensive cap on
    // how many branches one Parallel node can fan out to, same "pathological
    // hand-crafted graph" defense-in-depth posture as the loop bounds above
    // and OrchestratorService.MAX_STEPS_PER_RUN. Branch count comes straight
    // from the node's own outgoing edge count (an author-drawn graph, not a
    // runtime-computed list like Loop's item count), so this is a much
    // smaller number than the loop ceiling - nobody legitimately fans a
    // single decision out to more than a handful of concurrent branches.
    // Package-private (not private) so OrchestratorService.validate() can
    // enforce this same ceiling at save/activate time instead of only here
    // at run time - a graph with 11+ branches used to pass validation clean
    // and then fail on every actual run; see OrchestratorService's parallel
    // node check for the save-time half of this fix.
    static final int MAX_PARALLEL_BRANCHES = 10;

    /**
     * Dynamic Fan-Out node bounds - see {@link WorkflowNodeType}'s {@code
     * FAN_OUT} javadoc for why there are two separate caps here rather than
     * one, and why neither can be folded into {@link #MAX_PARALLEL_BRANCHES}.
     *
     * <p>{@code ITEMS} deliberately reuses Loop's exact numbers (25 default,
     * 100 hard ceiling): it bounds the same thing Loop's {@code
     * maxIterations} bounds - how much of a runaway runtime list gets worked
     * at all - and a second, differently-numbered ceiling for the identical
     * problem would be divergence for its own sake.
     *
     * <p>{@code CONCURRENCY} has no precedent to copy, because no construct
     * in this engine had one: the Parallel node's concurrency is implicitly
     * its branch count, which is why {@link #MAX_PARALLEL_BRANCHES} could get
     * away with being a single number. Once fan-out width is runtime data
     * that number stops bounding threads at all, so this is its own knob. The
     * ceiling of 10 is chosen against the actual deployment target rather
     * than an abstraction: production runs on a 1-2 vCPU VM, every worker
     * here does blocking I/O (an LLM/tool/channel call, plus {@code
     * executeNodeWithRetry}'s own {@code Thread.sleep} backoff), and 10
     * simultaneous blocked workers per fan-out node is already generous
     * against provider rate limits and the connection pool. The default of 5
     * is what an author gets without thinking about it and is deliberately
     * well under the ceiling.
     *
     * <p>Package-private so {@link OrchestratorService} can quote the same
     * numbers back to an author at save time instead of an author only ever
     * discovering them by having a value silently clamped at run time.
     */
    static final int DEFAULT_MAX_FAN_OUT_ITEMS = WorkflowTraversalRules.DEFAULT_MAX_FAN_OUT_ITEMS;
    static final int ABSOLUTE_MAX_FAN_OUT_ITEMS = WorkflowTraversalRules.ABSOLUTE_MAX_FAN_OUT_ITEMS;
    static final int DEFAULT_FAN_OUT_CONCURRENCY = WorkflowTraversalRules.DEFAULT_FAN_OUT_CONCURRENCY;
    static final int ABSOLUTE_MAX_FAN_OUT_CONCURRENCY = WorkflowTraversalRules.ABSOLUTE_MAX_FAN_OUT_CONCURRENCY;

    private final WorkflowRunRepository runRepository;
    private final OrchestratorService orchestratorService;
    private final WorkflowNodeExecutor nodeExecutor;
    private final WorkflowEventPublisher eventPublisher;
    // Issue record + bell for a failed run (best effort) - see RunFailureNotifier.
    private final RunFailureNotifier failureNotifier;
    // Version stamping (V273) - which graph snapshot this run executed,
    // so run-to-run diff can answer "ran different graphs" with data.
    private final ObjectProvider<WorkflowVersionService> versionService;
    private final WorkflowRunCancellationService cancellationService;
    private final WorkflowRunPauseService pauseService;
    private final WorkflowGraphSupport graphSupport;
    // Ends a traversal leg (cancelled / paused / waiting / halted / completed / failed) - see RunFinalizer.
    private final RunFinalizer finalizer;
    // Trace recording + live stream, and the shared handle the per-construct steps below receive.
    private final RunTraceRecorder recorder;
    private final TraversalKit kit;
    // Loop, parallel, fan_out, sub_flow and wait: the constructs the node loop cannot hand to the executor.
    private final List<ContainerStep> containerSteps;
    // Every other node: execute, error strategy, trace row, next hop.
    private final PlainNodeStep plainNodeStep;

    // Per-node error strategy (onError / retry, see NodeErrorStrategy). Field-injected, default OFF, so hand-built
    // test engines keep their constructor arity; off = a node's onError/retry config is ignored and every run is
    // byte-identical to before.
    @Value("${app.workflow.node-error-strategy.enabled:false}")
    private boolean nodeErrorStrategyEnabled = false;
    // Wait node's durable park; null in hand-built engines (a wait node then fails clearly instead of skipping).
    private WorkflowWaitService waitService;

    void setNodeErrorStrategyEnabled(boolean enabled) {
        this.nodeErrorStrategyEnabled = enabled;
    }

    @Autowired(required = false)
    void setWaitService(WorkflowWaitService waitService) {
        this.waitService = waitService;
    }

    /**
     * @param branchExecutor runs Parallel-node branches (JAVA-008). Was the common ForkJoinPool: parallelism =
     *     cores-1, i.e. ONE thread on a 2-vCPU container, shared JVM-wide - branches doing blocking LLM calls
     *     with sleep-based retry silently ran one after another and stalled every other pool user.
     * @param usageResolver nullable only for unit tests that construct the engine by hand
     * @param streamHub live run events (phase 3); nullable for hand-built test engines
     */
    public WorkflowExecutionEngine(
        WorkflowRunRepository runRepository,
        OrchestratorService orchestratorService,
        WorkflowNodeExecutor nodeExecutor,
        WorkflowEventPublisher eventPublisher,
        ObjectMapper objectMapper,
        PlatformIssueService issueService,
        ObjectProvider<NotificationService> notificationService,
        ObjectProvider<WorkflowVersionService> versionService,
        WorkflowRunCancellationService cancellationService,
        WorkflowRunPauseService pauseService,
        NodeUsageResolver usageResolver,
        WorkflowRunStreamHub streamHub,
        @Qualifier(PlatformExecutorConfiguration.PLATFORM_EXECUTOR) Executor branchExecutor
    ) {
        this.runRepository = runRepository;
        this.orchestratorService = orchestratorService;
        this.nodeExecutor = nodeExecutor;
        this.eventPublisher = eventPublisher;
        this.failureNotifier = new RunFailureNotifier(issueService, notificationService);
        this.versionService = versionService;
        this.cancellationService = cancellationService;
        this.pauseService = pauseService;
        this.graphSupport = new WorkflowGraphSupport(objectMapper);
        this.recorder = new RunTraceRecorder(runRepository, eventPublisher, graphSupport, objectMapper, usageResolver, streamHub);
        this.finalizer = new RunFinalizer(runRepository, cancellationService, eventPublisher, graphSupport,
            () -> this.waitService, failureNotifier::record, recorder::withRunData);
        this.kit = new TraversalKit(recorder, new NodeAttemptExecutor(nodeExecutor, streamHub), finalizer,
            graphSupport, cancellationService, nodeExecutor);
        this.containerSteps = List.of(
            new LoopStep(kit),
            new ParallelStep(kit, new ParallelBranchRunner(branchExecutor)),
            new FanOutStep(kit, new FanOutRunner()),
            new SubFlowStep(kit),
            new WaitStep(kit, nodeExecutor, () -> this.waitService));
        this.plainNodeStep = new PlainNodeStep(kit, objectMapper);
    }

    // Kept on the engine so existing callers/tests keep their import path.
    static long retryBackoffCapMillis(int attempt) {
        return WorkflowGraphSupport.retryBackoffCapMillis(attempt);
    }

    static long jitteredRetryDelayMillis(int attempt) {
        return WorkflowGraphSupport.jitteredRetryDelayMillis(attempt);
    }


    public WorkflowRun execute(WorkflowDefinition definition, WorkflowGraphDto graph, String input, String requestedBy) {
        return execute(definition, graph, null, input, requestedBy, null);
    }

    /**
     * @param clientRunId Real-time log viewer (#393): when the caller
     *     already generated a run id up front (the canvas's "Run" button
     *     does, precisely so it can start polling {@code GET
     *     .../runs/{runId}} before this still-synchronous call returns -
     *     see {@code WorkflowRunRequest}'s javadoc), that id is used
     *     instead of minting a new one, so the row this method saves
     *     immediately below is the exact row the caller is already polling
     *     for. {@code null} for every caller that doesn't care (webhook/
     *     schedule triggers) - a fresh random id, exactly as before.
     */
    public WorkflowRun execute(
        WorkflowDefinition definition, WorkflowGraphDto graph, String input, String requestedBy, UUID clientRunId
    ) {
        return execute(definition, graph, null, input, requestedBy, clientRunId);
    }

    /**
     * Per-node workflow triggers - each trigger node in a graph can now be
     * independently bound to its own inbound channel (see {@code
     * WorkflowService#setTriggerChannel}), so a run must be told WHICH
     * trigger node started it rather than always assuming "the sole Trigger
     * node in the graph".
     *
     * @param startNodeId the id of the Trigger node this run should start
     *     from, or {@code null} to keep the pre-per-node-trigger behavior of
     *     using the first Trigger node found in the graph (the manual "Run"
     *     button and the schedule trigger both still use {@code null} - they
     *     have no channel-specific node to resolve, see {@code
     *     WorkflowService#run}'s no-startNodeId overloads).
     */
    public WorkflowRun execute(
        WorkflowDefinition definition, WorkflowGraphDto graph, String startNodeId, String input, String requestedBy, UUID clientRunId
    ) {
        return execute(definition, graph, startNodeId, input, requestedBy, clientRunId, List.of());
    }

    /**
     * Fullest entry point - additionally carries the run's optional inline
     * attachments (Workflow Share mic/video, {@code WorkflowRunRequest.attachments}).
     * Every overload above delegates here with {@code List.of()}, so nothing
     * about their behavior changes. See {@link #runTraversal}'s {@code
     * pendingAttachments} handling for exactly which node (if any) actually
     * receives these.
     */
    public WorkflowRun execute(
        WorkflowDefinition definition, WorkflowGraphDto graph, String startNodeId, String input, String requestedBy,
        UUID clientRunId, List<ChatInlineAttachment> attachments
    ) {
        return execute(definition, graph, startNodeId, input, requestedBy, clientRunId, attachments, RunMode.PRODUCTION);
    }

    /**
     * As above, in an explicit {@link RunMode}. Every overload without a mode is PRODUCTION, which never reads a
     * pin; only the canvas Run path (`WorkflowService#run` with DRAFT) passes anything else.
     */
    public WorkflowRun execute(
        WorkflowDefinition definition, WorkflowGraphDto graph, String startNodeId, String input, String requestedBy,
        UUID clientRunId, List<ChatInlineAttachment> attachments, RunMode mode
    ) {
        return execute(definition, graph, startNodeId, input, requestedBy, clientRunId, attachments, mode, null);
    }

    /** As above, recording the run's optional operator comment on the row (never on the node input). */
    public WorkflowRun execute(
        WorkflowDefinition definition, WorkflowGraphDto graph, String startNodeId, String input, String requestedBy,
        UUID clientRunId, List<ChatInlineAttachment> attachments, RunMode mode, String comment
    ) {
        WorkflowRun run = prepareRun(definition, graph, clientRunId, requestedBy, mode, comment);
        return continueRun(definition, graph, run, startNodeId, input, requestedBy, attachments);
    }

    /**
     * The validate-and-persist half of {@link #execute}, split out (2026-08-17,
     * background runs) so {@link WorkflowBackgroundRunService} can create the
     * pollable run row ON THE REQUEST THREAD - a validation failure still
     * becomes an ordinary HTTP error with no row, and the caller gets a run id
     * whose row is guaranteed to exist before the response is sent (the
     * console's live poller tolerates a brief 404, but an API caller given a
     * run id should never see one) - while {@link #continueRun} does the
     * actual node traversal on a worker thread.
     */
    public WorkflowRun prepareRun(
        WorkflowDefinition definition, WorkflowGraphDto graph, UUID clientRunId, String requestedBy
    ) {
        return prepareRun(definition, graph, clientRunId, requestedBy, RunMode.PRODUCTION);
    }

    /** As above, persisting the run's {@link RunMode} on the row so resumes and recovery keep it. */
    public WorkflowRun prepareRun(
        WorkflowDefinition definition, WorkflowGraphDto graph, UUID clientRunId, String requestedBy, RunMode mode
    ) {
        return prepareRun(definition, graph, clientRunId, requestedBy, mode, null);
    }

    /** As above, also stamping the run's optional operator comment. */
    public WorkflowRun prepareRun(
        WorkflowDefinition definition, WorkflowGraphDto graph, UUID clientRunId, String requestedBy, RunMode mode,
        String comment
    ) {
        // Defense in depth: OrchestratorService.validate() already gates saves
        // and activation, but re-checking here means a run can never bypass
        // governance even if it somehow reaches this method with a graph that
        // was saved before a validation rule existed, or an ACTIVE workflow
        // whose validity assumptions have since changed (e.g. its Agent node
        // now points at a deleted agent).
        orchestratorService.validate(graph, definition.getId());

        UUID runId = clientRunId != null ? clientRunId : UUID.randomUUID();
        WorkflowRun run = new WorkflowRun(runId, definition.getId(), definition.getName(), requestedBy);
        run.setRunMode(mode);
        run.setComment(comment);
        // Version stamping (V273): the snapshot ordinal this run executes
        // - null when the workflow predates versioning or the service is
        // absent, an honest "unknown" the diff endpoint renders as such.
        WorkflowVersionService versions = versionService.getIfAvailable();
        if (versions != null) {
            run.setWorkflowVersion(versions.currentVersionNumber(definition.getId()));
        }
        runRepository.save(run);

        eventPublisher.publish("workflow.run.started",
            runId.toString(), Map.of("workflowId", definition.getId().toString(), "workflowName", definition.getName()));
        return run;
    }

    /** The traversal half of {@link #execute} - see {@link #prepareRun}. */
    public WorkflowRun continueRun(
        WorkflowDefinition definition, WorkflowGraphDto graph, WorkflowRun run, String startNodeId, String input,
        String requestedBy, List<ChatInlineAttachment> attachments
    ) {
        return recorder.streamed(run, definition, false, () -> runTraversal(definition, graph, run, new ArrayList<>(),
            requestedBy, startNodeId, input, null, null, 0, attachments));
    }

    /**
     * Approval-node resume (F-05, second half): re-enters this engine's
     * traversal mid-graph, at the node a human just approved, on a run whose
     * status the caller ({@link WorkflowApprovalActionExecutor}) has already
     * atomically flipped AWAITING_APPROVAL &rarr; RUNNING. Completed nodes
     * are NOT re-executed - the linear-walk design means everything the rest
     * of the graph needs from them is the single {@code currentOutput} the
     * halted node was about to receive, carried here as {@code resumeInput}
     * (recorded in the approval request's payload at halt time; trace
     * entries hold summaries, not outputs, so the trace alone could never
     * reconstruct it). The prior trace is carried forward verbatim and new
     * entries append after it, so the run's history reads as one timeline:
     * ...&nbsp;AWAITING_APPROVAL halt&nbsp;... approval&nbsp;... resumed
     * nodes.
     *
     * <p>The caller is expected to hold an {@code ApprovalReplayGrant} for
     * the approved action type - that is what lets the resumed node itself
     * pass back through {@code WorkflowNodeExecutor}'s approval gate instead
     * of halting all over again (see that class).
     *
     * <p>Honest limitations, all documented rather than papered over:
     * <ul>
     *   <li>The run resumes against the workflow's CURRENT graph - if the
     *       graph was edited while the request sat pending, downstream nodes
     *       run in their edited form, and a resume node deleted from the
     *       graph fails the run with a clear reason instead of guessing.</li>
     *   <li>An approval that halted INSIDE a Loop/Parallel construct (as
     *       the loop's body node or a parallel branch) resumes the plain
     *       linear walk from that node - remaining loop iterations /sibling
     *       branches of the interrupted construct are not replayed.</li>
     *   <li>{@code stepsAlreadyUsed} is seeded from the prior trace's entry
     *       count - a faithful-enough proxy for the steps the first leg
     *       consumed (the engine records one entry per executed step) that
     *       keeps {@code maxStepsPerRun} a genuine whole-run ceiling.</li>
     * </ul>
     *
     * <p>The bookkeeping trace entry appended below is load-bearing beyond
     * cosmetics: {@code WorkflowRunRecoveryJob} treats the newest trace
     * timestamp as the run's heartbeat, and a run resumed days after its
     * {@code startedAt} would otherwise sit in the reaper's candidate window
     * with only stale timestamps until the first resumed node completes.
     */
    public WorkflowRun resume(
        WorkflowDefinition definition, WorkflowGraphDto graph, WorkflowRun run,
        List<NodeTraceEntry> priorTrace, String resumeNodeId, String resumeInput, String requestedBy
    ) {
        UUID runId = run.getId();
        List<NodeTraceEntry> trace = new ArrayList<>(priorTrace);
        WorkflowNodeDto resumeNode = graph.nodes().stream()
            .filter(n -> n.id().equals(resumeNodeId))
            .findFirst()
            .orElse(null);
        try {
            // Same defense-in-depth re-validation execute() performs - a
            // resumed run must not become the one path that skips governance.
            orchestratorService.validate(graph, definition.getId());
            if (resumeNode == null) {
                throw new WorkflowValidationException(List.of(
                    "Node " + resumeNodeId + " no longer exists in workflow '" + definition.getName()
                        + "' - the graph changed while approval was pending, so this run cannot be resumed."));
            }
        } catch (RuntimeException e) {
            // The caller already flipped the run to RUNNING - failing here
            // must leave a terminal, explained row, not a stuck RUNNING one
            // (which WorkflowRunRecoveryJob would eventually reap with a
            // misleading "instance stopped mid-run" reason).
            Instant now = Instant.now();
            trace.add(new NodeTraceEntry(resumeNodeId, resumeNode != null ? resumeNode.type() : "engine",
                resumeNode != null ? resumeNode.label() : "Resume", "ERROR", e.getMessage(), now, now,
                List.of("Resume after approval failed: " + e.getMessage())));
            run.complete(WorkflowRunStatus.FAILED, graphSupport.writeTrace(trace), e.getMessage(),
                run.getOutputFileName(), run.getOutputData());
            runRepository.save(run);
            failureNotifier.record(run, e.getMessage());
            eventPublisher.publish("workflow.run.failed", runId.toString(),
                Map.of("workflowId", definition.getId().toString(), "error", String.valueOf(e.getMessage())));
            return run;
        }

        eventPublisher.publish("workflow.run.resumed", runId.toString(), Map.of(
            "workflowId", definition.getId().toString(), "nodeId", resumeNodeId));
        Instant now = Instant.now();
        // A run parked by a Wait node was already given its "Wait finished" row by WorkflowWaitResumeService, so
        // the bookkeeping row must not claim an approval happened.
        boolean afterWait = !priorTrace.isEmpty()
            && "wait".equalsIgnoreCase(priorTrace.get(priorTrace.size() - 1).nodeType());
        trace.add(recorder.withRunData(trace, new NodeTraceEntry(resumeNode.id(), resumeNode.type(), resumeNode.label(), "OK",
            afterWait ? "Wait over - resuming run from this node." : "Approval granted - resuming run from this node.",
            now, now, List.of()), resumeNode, resumeInput, null, NodeTracePayloads.MAX_FIELD_CHARS, null));
        run.updateTrace(graphSupport.writeTrace(trace));
        runRepository.save(run);

        // Workflow Share mic/video - an approval-gated entry node loses its
        // camera attachment on resume, since the attachment was only ever
        // available at the original run() call and the approval payload has
        // no field for binary data. Documented gap, not a bug - see
        // WorkflowRunRequest.attachments' javadoc.
        return recorder.streamed(run, definition, true, () -> runTraversal(definition, graph, run, trace, requestedBy,
            null, null, resumeNodeId, resumeInput, trace.size(), List.of()));
    }
    /**
     * The traversal loop {@code execute()} always ran, extracted verbatim so
     * {@link #resume} can re-enter it mid-graph - the ONLY behavioral
     * additions are the resume-entry branch at the top (skip trigger
     * resolution, start from {@code resumeNodeId} with {@code resumeOutput}
     * as the in-flight value) and seeding {@code fileOutput}/{@code steps}
     * from the run's persisted state instead of empty/zero (both are
     * empty/zero for a fresh run, so {@code execute()}'s behavior is
     * bit-identical to before the extraction). Exactly one of
     * ({@code startNodeId}/{@code input}) and ({@code resumeNodeId}/
     * {@code resumeOutput}) is meaningful per call, discriminated on
     * {@code resumeNodeId != null}.
     *
     * <p>Loop, parallel, fan_out, sub_flow and wait are not run here: each is a {@link ContainerStep} the loop
     * dispatches to, advancing the shared {@link RunTraversalState}.
     */
    private WorkflowRun runTraversal(
        WorkflowDefinition definition, WorkflowGraphDto graph, WorkflowRun run, List<NodeTraceEntry> trace,
        String requestedBy, String startNodeId, String input, String resumeNodeId, String resumeOutput,
        int stepsAlreadyUsed, List<ChatInlineAttachment> initialAttachments
    ) {
        UUID runId = run.getId();
        // The state carries the node-by-id and outgoing-edge indexes, the file artifact seeded from the run row
        // (so a resumed run keeps a file produced before the approval halt), the step budget, and which node is
        // "active" - tracked on the state, outside the try block below, so the catch block blames the node that
        // was actually executing and not the last one that finished. Starts "unknown" so a failure before the
        // trigger is resolved still produces a coherent trace entry instead of an NPE.
        RunTraversalState s = new RunTraversalState(definition, graph, run, trace, requestedBy, stepsAlreadyUsed);
        final RunMode mode = s.mode;

        try {
            String currentOutput;
            String currentNodeId;
            if (resumeNodeId != null) {
                // Approval resume: the node the human approved executes next
                // (its own re-entry through the approval gate is the
                // caller-held ApprovalReplayGrant's job), fed the exact input
                // it was about to receive when the run halted.
                currentNodeId = resumeNodeId;
                currentOutput = resumeOutput;
                s.active(resumeNodeId, resumeOutput);
            } else {
                // Per-node workflow triggers: startNodeId != null means this run
                // was fired by a specific inbound-channel binding (see
                // WorkflowService#setTriggerChannel/WorkflowTriggerPortImpl) and
                // MUST start from that exact Trigger node, not just any Trigger
                // node in the graph - a graph can now legitimately have more than
                // one. null keeps the pre-per-node-trigger fallback (manual Run
                // button, schedule trigger) of using the first Trigger node found.
                WorkflowNodeDto trigger = startNodeId != null
                    ? graph.nodes().stream()
                        .filter(n -> n.id().equals(startNodeId) && "trigger".equalsIgnoreCase(n.type()))
                        .findFirst()
                        .orElseThrow(() -> new WorkflowValidationException(
                            List.of("Start node " + startNodeId + " is not a valid Trigger node in this graph.")))
                    : graph.nodes().stream()
                        .filter(n -> "trigger".equalsIgnoreCase(n.type()))
                        .findFirst()
                        .orElseThrow(() -> new WorkflowValidationException(List.of("No Trigger node found.")));
                s.activeNodeId = trigger.id();

                currentOutput = input != null && !input.isBlank() ? input : "Workflow started manually.";
                currentNodeId = trigger.id();
            }
            Set<String> visited = new HashSet<>();

            // One-shot: consumed by whichever single node executes immediately
            // after the Trigger node (the only node whose input is genuinely
            // the run's raw, unprocessed payload) - every node after that only
            // ever sees a prior node's derived text output, never this. See
            // WorkflowRunRequest.attachments' javadoc.
            List<ChatInlineAttachment> pendingAttachments = resumeNodeId == null
                ? (initialAttachments == null ? List.of() : initialAttachments)
                : List.of();

            while (currentNodeId != null) {
                // SE-53: the ERROR trace row names the node about to run (the one the ceiling refused), not the
                // previous node, whose own row is already in the trace.
                s.active(currentNodeId, currentOutput);
                s.chargeStep();
                if (!visited.add(currentNodeId)) {
                    // Should be unreachable post-validation (cycles are rejected),
                    // but the step ceiling above is the real backstop either way.
                    break;
                }

                WorkflowNodeDto node = s.nodesById.get(currentNodeId);

                // Workflow Share mic/video: hand the one-shot pendingAttachments
                // to whichever single node runs immediately after Trigger, then
                // spend it - never forwarded a second hop, even into a Loop's
                // repeated body or a Parallel fan-out (the container steps
                // deliberately do NOT receive attachmentsForThisNode).
                List<ChatInlineAttachment> attachmentsForThisNode;
                if (!pendingAttachments.isEmpty() && !"trigger".equalsIgnoreCase(node.type())) {
                    attachmentsForThisNode = pendingAttachments;
                    pendingAttachments = List.of();
                } else {
                    attachmentsForThisNode = List.of();
                }

                // Stop Run - checked BETWEEN node executions, right where the
                // traversal is about to hand off to the next node, exactly
                // like every other engine-level decision this loop already
                // makes here (step ceiling, cycle detection above). A node
                // already mid-flight when a cancel request lands is NOT
                // interrupted - this only ever stops the run from STARTING
                // its next node, an accepted, documented limitation (see
                // WorkflowRunCancellationService's javadoc) rather than a
                // claimed instant-stop guarantee.
                if (cancellationService.isCancellationRequested(runId)) {
                    return kit.cancelled(s, node, "");
                }

                // Pause Run (V261) - checked at the same between-node
                // checkpoint as cancel, deliberately AFTER it: a cancel
                // racing a pause wins (stop is the stronger operator signal -
                // a cancelled run can never be resumed, a paused one always
                // can). The pause flag is consumed here so a stale flag can
                // never re-park the run mid-resume; the envelope on the run
                // row is what POST .../resume re-enters from. Not checked
                // inside Loop/Parallel iteration bodies: resuming into the
                // middle of an iteration set has no faithful envelope
                // (iteration index is traversal state, not node state), so a
                // pause during a long loop parks at the NEXT real node
                // boundary - the documented between-nodes semantic.
                if (pauseService.isPauseRequested(runId)) {
                    pauseService.clearPauseFlag(runId);
                    return finalizePaused(s, node, currentOutput);
                }

                ContainerStep container = containerStepFor(node);
                if (container != null) {
                    ContainerStep.Outcome outcome = container.run(s, node, currentOutput);
                    if (outcome.finished() != null) {
                        return outcome.finished();
                    }
                    currentOutput = outcome.nextOutput();
                    currentNodeId = outcome.nextNodeId();
                    continue;
                }

                ContainerStep.Outcome outcome = plainNodeStep.run(
                    s, node, currentOutput, attachmentsForThisNode, nodeErrorStrategyEnabled);
                if (outcome.finished() != null) {
                    return outcome.finished();
                }
                currentOutput = outcome.nextOutput();
                currentNodeId = outcome.nextNodeId();
            }

            if (mode != RunMode.PRODUCTION) {
                // The run summary: a green draft run must say how much of it was real. The same count is on the
                // response (WorkflowRunResponse#pinnedNodeCount), derived from these PINNED trace entries.
                log.info("{} run {} of '{}' completed; {} node(s) used pinned data", mode, runId,
                    definition.getName(), pinnedCount(trace));
            }
            return finalizer.finalizeLeg(s.leg(), new RunFinalizer.Completed());

        } catch (RuntimeException e) {
            log.warn("Workflow run {} for '{}' failed: {}", runId, definition.getName(), e.getMessage());
            // activeNodeId names the node that was actually executing when
            // this exception was thrown - NOT trace's last entry, which is
            // the last node that already finished successfully. Looking the id back up in nodesById
            // (rather than reusing a generic "engine"/"Engine" label) means a
            // failed run's trace shows the real failing node's type and
            // label, matching every other trace entry's shape.
            String failedNodeId = s.activeNodeId;
            WorkflowNodeDto failedNode = s.nodesById.get(failedNodeId);
            return finalizer.finalizeLeg(s.leg(), new RunFinalizer.Failed(failedNodeId, failedNode, s.activeInput, e));
        }
    }

    private ContainerStep containerStepFor(WorkflowNodeDto node) {
        for (ContainerStep step : containerSteps) {
            if (step.nodeType().equalsIgnoreCase(node.type())) {
                return step;
            }
        }
        return null;
    }

    /** Trace entries served from a pin instead of executing ({@code PINNED}). */
    static long pinnedCount(List<NodeTraceEntry> trace) {
        return trace.stream().filter(e -> "PINNED".equals(e.status())).count();
    }

    /**
     * Pause Run (V261) - the terminal path for THIS traversal leg (the run
     * itself is not terminal: PAUSED parks it for {@code POST .../resume}).
     * {@code nextNode}/{@code nextNodeInput} are exactly the pair the
     * traversal was about to hand off to - persisted as the resume envelope
     * on the run row (V261 columns) so {@link WorkflowBackgroundRunService}'s
     * resume can re-enter through {@link #resume} at that node with that
     * input, the same re-entry contract approval-resume uses. Trace and
     * envelope are saved in one write so a crash between them can never leave
     * a PAUSED row pointing at a node the trace doesn't reach.
     */
    private WorkflowRun finalizePaused(RunTraversalState s, WorkflowNodeDto nextNode, String nextNodeInput) {
        return finalizer.finalizeLeg(s.legWithoutFile(), new RunFinalizer.Paused(nextNode, nextNodeInput));
    }

}
