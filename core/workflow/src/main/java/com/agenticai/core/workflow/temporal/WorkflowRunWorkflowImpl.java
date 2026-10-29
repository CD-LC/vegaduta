package com.agenticai.core.workflow.temporal;

import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.NodeTracePayloads;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.WorkflowTraversalRules;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Durable counterpart to {@code WorkflowExecutionEngine.execute}'s node-walking loop. Graph
 * traversal (building {@code nodesById}/{@code outgoing}, finding the trigger, walking edges) is
 * pure logic over the immutable {@link WorkflowRunInput} this execution started with, so it replays
 * identically every time Temporal needs to rebuild this workflow's state (after a worker
 * crash/restart, or simply to process a new event) - the actual work, and every side effect,
 * happens in {@link WorkflowNodeActivities} calls instead.
 *
 * <p><b>Why this can't just be {@code WorkflowExecutionEngine}'s loop verbatim</b>: Temporal
 * requires Workflow method code to be deterministic - safe to re-execute from the top on every
 * replay and always reach the same decisions. That rules out anything the original loop did
 * directly: {@code Instant.now()} (timestamps come back from the {@code executeNode} Activity
 * instead, captured on a real, un-replayed thread - see {@link NodeExecutionActivityResult}'s
 * javadoc), direct DB writes/Kafka publishes ({@code runRepository.save}/{@code
 * eventPublisher.publish} - both moved into {@link WorkflowNodeActivities#recordProgress}), and any
 * I/O of any kind (the agent/tool/channel calls themselves - moved into {@link
 * WorkflowNodeActivities#executeNode}). Plain SLF4J logging is also replaced with {@link
 * Workflow#getLogger} for the same reason (an ordinary logger would log every line again on every
 * replay).
 *
 * <p><b>Delivery semantics, stated honestly</b>: Temporal Activities are at-least-once by default -
 * a genuinely successful {@code executeNode} call whose acknowledgment is lost (worker crash
 * between "activity finished" and "Temporal recorded that") will be retried, which could re-send an
 * Output(email/telegram/whatsapp) node's message a second time. None of those destination services
 * are idempotency-key-aware today, so this stays an honest, documented limitation rather than a
 * claimed guarantee - true exactly-once delivery for those specific side effects is a real
 * follow-up (idempotency keys threaded through {@code EmailOutputService}/{@code
 * TelegramOutputService}/{@code WhatsAppOutputService}), not solved by adopting Temporal alone.
 * What Temporal genuinely does solve here, and is this stage's actual Definition of Done: a
 * killed-and-restarted worker resumes an in-flight run from its last COMPLETED node, never from
 * scratch.
 *
 * <p><b>Shape of the split</b>: this class keeps only the workflow/activity wiring - the stubs, the
 * node walk, the approval park on {@code Workflow.await}, and the terminal handling. Loop, parallel
 * and fan_out are {@link TemporalStep}s ({@link TemporalLoopStep}, {@link TemporalParallelStep},
 * {@link TemporalFanOutStep}) over a shared {@link TemporalRunState}; the parallel and fan-out
 * steps fan out with {@code Async.function}/{@code Promise.allOf} - Temporal's
 * deterministic-replay-safe substitute for the {@code CompletableFuture} concurrency {@code
 * WorkflowExecutionEngine} uses (raw threads race non-deterministically across replays). A real
 * change to any step's decision logic needs a {@link Workflow#getVersion} guard so already-recorded
 * histories keep replaying their original decisions; each step documents its changeId, and future
 * changes follow the same pattern (a new changeId, or a bumped {@code maxSupported}). {@code
 * WorkflowRunWorkflowHistoryFixturesTest} replays histories recorded before the split and fails on
 * any change to the command sequence.
 */
public class WorkflowRunWorkflowImpl implements WorkflowRunWorkflow {

    private static final Logger log = Workflow.getLogger(WorkflowRunWorkflowImpl.class);

    // The loop / fan-out bounds and next-hop rules live in the pure, deterministic
    // WorkflowTraversalRules, shared
    // with WorkflowExecutionEngine. Loop, parallel and fan_out are TemporalSteps (see
    // TemporalRunState for the
    // determinism contract they share); each owns its own Workflow.getVersion changeId.
    private static final List<TemporalStepBinding> STEPS =
            List.of(
                    new TemporalStepBinding("loop", new TemporalLoopStep()),
                    new TemporalStepBinding("parallel", new TemporalParallelStep()),
                    new TemporalStepBinding("fan_out", new TemporalFanOutStep()));

    private record TemporalStepBinding(String nodeType, TemporalStep step) {}

    // Workflow.getVersion changeId for the signal-based approval-resume
    // branch below (the "parked workflow" path): previously a node that
    // halted AWAITING_APPROVAL ended this execution immediately, and the
    // later human decision re-entered the graph through the synchronous
    // WorkflowExecutionEngine#resume - in-process on the deciding thread,
    // with none of Temporal's durability. Executions whose histories were
    // recorded under that shape get Workflow.DEFAULT_VERSION here and keep
    // returning on halt exactly as originally executed; v1+ executions park
    // inside this workflow on Workflow.await instead, and the decision
    // arrives as the approvalDecision signal.
    private static final String AWAIT_APPROVAL_RESUME_CHANGE_ID = "await-approval-resume-v1";

    // How long a parked run waits for its approval decision before failing
    // out of the wait. Measured in days because real human decision latency
    // is hours-to-days; the run row reads AWAITING_APPROVAL for the whole
    // wait (the same shape the legacy engine persisted at halt), so nothing
    // downstream needs a new state, and Stop Run still works - cancellation
    // interrupts Workflow.await like any other blocking call here. Seven
    // days matches HumanApprovalGateService's standing request-expiry
    // posture rather than being a new policy invented here.
    private static final Duration APPROVAL_WAIT_TIMEOUT = Duration.ofDays(7);

    // Latest approvalDecision signal awaiting consumption by a parked
    // AWAITING_APPROVAL branch, or null while none has arrived. Signal
    // deliveries themselves are recorded in this execution's event history,
    // so a replay after a worker restart restores this field's value
    // faithfully - the parked state survives crashes, which is the entire
    // point of doing resume inside the durable workflow.
    private WorkflowApprovalSignal pendingApproval;

    private final WorkflowNodeActivities progressActivities =
            Workflow.newActivityStub(
                    WorkflowNodeActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(30))
                            .setRetryOptions(
                                    RetryOptions.newBuilder().setMaximumAttempts(5).build())
                            .build());

    @Override
    public WorkflowRunResult run(WorkflowRunInput input) {
        TemporalRunState s =
                new TemporalRunState(
                        input,
                        nodeActivityStub(input, Math.max(0, input.maxRetryAttempts()) + 1),
                        nodeActivityStub(input, 1),
                        progressActivities);

        // Per-node workflow triggers - mirrors WorkflowExecutionEngine#execute's
        // identical startNodeId handling (see that method's javadoc): a
        // non-null startNodeId means this run was fired by a specific
        // inbound-channel binding and MUST start from that exact Trigger
        // node, since a graph can now legitimately have more than one.
        WorkflowGraphDto graph = input.graph();
        WorkflowNodeDto trigger =
                input.startNodeId() != null
                        ? graph.nodes().stream()
                                .filter(
                                        n ->
                                                n.id().equals(input.startNodeId())
                                                        && "trigger".equalsIgnoreCase(n.type()))
                                .findFirst()
                                .orElse(null)
                        : graph.nodes().stream()
                                .filter(n -> "trigger".equalsIgnoreCase(n.type()))
                                .findFirst()
                                .orElse(null);
        if (trigger == null) {
            // Should be unreachable for the null-startNodeId path -
            // TemporalWorkflowExecutionEngine already called
            // OrchestratorService.validate(graph) before starting this
            // workflow - but fail the run cleanly rather than NPE if it ever
            // is reached (e.g. a future caller that skips that pre-check, or
            // a startNodeId that no longer names a real Trigger node).
            String message =
                    input.startNodeId() != null
                            ? "Start node "
                                    + input.startNodeId()
                                    + " is not a valid Trigger node in this graph."
                            : "No Trigger node found.";
            return s.terminate(WorkflowRunStatus.FAILED, message);
        }

        // Fail-proof hardening - same fix as WorkflowExecutionEngine#execute's
        // active-node tracking: s.activeNodeId is the node actually executing,
        // so the catch blocks below attribute a failure to the real failing
        // node, not the last node that already finished (trace's last entry).
        s.activeNodeId = trigger.id();

        try {
            String currentOutput =
                    input.input() != null && !input.input().isBlank()
                            ? input.input()
                            : "Workflow started manually.";
            String currentNodeId = trigger.id();
            Set<String> visited = new HashSet<>();

            while (currentNodeId != null) {
                s.chargeStep();
                if (!visited.add(currentNodeId)) {
                    break;
                }

                WorkflowNodeDto node = s.nodesById.get(currentNodeId);
                s.activeNodeId = currentNodeId;

                // Loop / Parallel / Dynamic Fan-Out: special-cased constructs, each in its own
                // TemporalStep.
                TemporalStep step = stepFor(node);
                if (step != null) {
                    TemporalStep.Outcome outcome = step.run(s, node, currentOutput);
                    if (outcome.finished() != null) {
                        return outcome.finished();
                    }
                    currentOutput = outcome.nextOutput();
                    currentNodeId = outcome.nextNodeId();
                    continue;
                }

                // Approval-aware execution of the CURRENT node: a normal
                // node runs once; a node that halts AWAITING_APPROVAL parks
                // THIS execution (v1+) and re-executes here when its
                // decision signal arrives - the durable replacement for
                // WorkflowExecutionEngine#resume's in-process leg.
                NodeExecutionActivityResult result;
                String replayGrant = null;
                while (true) {
                    result =
                            s.nodeActivitiesFor(node)
                                    .executeNode(
                                            s.nodeInput(node, currentOutput, null)
                                                    .withApprovalReplay(replayGrant));
                    replayGrant = null;

                    s.trace.add(
                            s.withPayloads(
                                    new NodeTraceEntry(
                                            node.id(),
                                            node.type(),
                                            node.label(),
                                            result.status(),
                                            result.summary(),
                                            Instant.ofEpochMilli(result.startedAtEpochMilli()),
                                            Instant.ofEpochMilli(result.completedAtEpochMilli()),
                                            result.logLines()),
                                    currentOutput,
                                    result.output(),
                                    NodeTracePayloads.MAX_FIELD_CHARS));
                    s.captureFile(result);

                    if (result.haltRun() == null) {
                        break;
                    }

                    WorkflowRunStatus haltStatus = WorkflowRunStatus.valueOf(result.haltRun());
                    boolean parkedApprovalWait =
                            haltStatus == WorkflowRunStatus.AWAITING_APPROVAL
                                    && Workflow.getVersion(
                                                    AWAIT_APPROVAL_RESUME_CHANGE_ID,
                                                    Workflow.DEFAULT_VERSION,
                                                    1)
                                            == 1;
                    if (!parkedApprovalWait) {
                        // Pre-signal histories (DEFAULT_VERSION) and every
                        // non-approval halt take the original path: persist
                        // terminal, end the execution.
                        return s.terminate(haltStatus, result.summary());
                    }

                    // The run row gets the same terminal-shaped
                    // AWAITING_APPROVAL write the legacy engine made at
                    // halt, so the runs list, the approvals inbox and
                    // WorkflowApprovalActionExecutor's CAS claim all see
                    // exactly the state they're built for - but this
                    // execution stays ALIVE on Workflow.await instead of
                    // returning. The decision arrives as an
                    // approvalDecision signal from
                    // WorkflowApprovalActionExecutor (which CAS-claims the
                    // row BEFORE signaling - the row, not this field, is
                    // the authority on who decided what).
                    s.recordTerminal(haltStatus, result.summary());

                    WorkflowApprovalSignal decision =
                            awaitApprovalDecision(input.runId(), node.id());
                    if (decision == null) {
                        String expired =
                                "Approval for node '"
                                        + node.label()
                                        + "' expired after "
                                        + APPROVAL_WAIT_TIMEOUT.toDays()
                                        + " days with no decision.";
                        Instant expiredAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());
                        s.trace.add(
                                new NodeTraceEntry(
                                        node.id(),
                                        node.type(),
                                        node.label(),
                                        "ERROR",
                                        expired,
                                        expiredAt,
                                        expiredAt,
                                        List.of()));
                        return s.terminate(WorkflowRunStatus.FAILED, expired);
                    }
                    if (!decision.approved()) {
                        // onRejected already CAS-claimed and terminal-wrote
                        // the row (decider + reason on the trace) - this
                        // signal is only the wake-up, and writing again
                        // here would clobber that authoritative entry.
                        String rejected =
                                "Approval rejected"
                                        + (decision.decidedBy() != null
                                                ? " by " + decision.decidedBy()
                                                : "")
                                        + (decision.reason() != null
                                                ? ": " + decision.reason()
                                                : ".");
                        return new WorkflowRunResult(
                                WorkflowRunStatus.FAILED.name(), rejected, s.fileName, s.fileData);
                    }

                    // Approved: re-execute THE SAME node, carrying the
                    // replay grant through the activity input so
                    // WorkflowNodeExecutor's gate lets the already-approved
                    // execution through (the durable-boundary equivalent of
                    // the legacy engine's same-thread ApprovalReplayGrant -
                    // it can't cross the workflow/activity line, so it
                    // travels as explicit data like tenant identity does).
                    Instant resumedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());
                    s.trace.add(
                            new NodeTraceEntry(
                                    node.id(),
                                    node.type(),
                                    node.label(),
                                    "OK",
                                    "Approval granted"
                                            + (decision.decidedBy() != null
                                                    ? " by " + decision.decidedBy()
                                                    : "")
                                            + " - resuming run from this node.",
                                    resumedAt,
                                    resumedAt,
                                    List.of()));
                    replayGrant = "workflow.node." + node.type().toLowerCase(Locale.ROOT);
                }

                s.recordProgress(node);

                currentOutput = result.output();
                currentNodeId =
                        WorkflowTraversalRules.nextNodeIdFirstEdge(
                                s.edgesOf(node.id()), result.branch());
            }

            return s.terminate(WorkflowRunStatus.COMPLETED, null);

        } catch (CanceledFailure e) {
            // Stop Run (client-initiated, see TemporalWorkflowExecutionEngine
            // #cancelRun's javadoc for the full mechanism and its honest
            // limitations) - MUST be caught ahead of the generic RuntimeException
            // branch below since CanceledFailure extends TemporalFailure extends
            // RuntimeException; without this dedicated branch a cancelled run
            // would previously have been recorded as FAILED, which is a
            // materially different, misleading outcome for a user who
            // deliberately clicked Stop.
            log.warn(
                    "Workflow run {} for '{}' was cancelled by user request.",
                    input.runId(),
                    input.workflowName());
            return s.terminateAtActiveNode(
                    WorkflowRunStatus.CANCELLED,
                    "Cancelled by user request.",
                    "CANCELLED",
                    "Run cancelled by user request.",
                    List.of());

        } catch (RuntimeException e) {
            log.warn(
                    "Workflow run {} for '{}' failed: {}",
                    input.runId(),
                    input.workflowName(),
                    e.getMessage());
            // Workflow.currentTimeMillis() (not Instant.now()) - Temporal's
            // replay-safe substitute for the wall clock inside workflow code,
            // see this class's javadoc.
            return s.terminateAtActiveNode(
                    WorkflowRunStatus.FAILED,
                    e.getMessage(),
                    "ERROR",
                    e.getMessage(),
                    List.of("Run failed: " + e.getMessage()));
        }
    }

    private static TemporalStep stepFor(WorkflowNodeDto node) {
        for (TemporalStepBinding binding : STEPS) {
            if (binding.nodeType().equalsIgnoreCase(node.type())) {
                return binding.step();
            }
        }
        return null;
    }

    /**
     * The node-execution activity stub for one run: heartbeating (see {@code
     * WorkflowNodeActivitiesImpl#executeNode}, which beats every 10s - 45s gives four missed-tick
     * headroom before Temporal declares the activity dead and retries it, which is also what lets
     * Stop Run interrupt an in-flight node rather than waiting out stepTimeoutSeconds), retried per
     * the run's own policy. {@code maxAttempts} is TOTAL attempts; irreversible side-effect nodes
     * get a {@code 1}-attempt twin of this stub (see {@link TemporalRunState#nodeActivitiesFor}),
     * so a Temporal activity retry cannot double their send either.
     */
    private static WorkflowNodeActivities nodeActivityStub(
            WorkflowRunInput input, int maxAttempts) {
        return Workflow.newActivityStub(
                WorkflowNodeActivities.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(
                                Duration.ofSeconds(
                                        input.stepTimeoutSeconds() > 0
                                                ? input.stepTimeoutSeconds()
                                                : 30))
                        .setHeartbeatTimeout(Duration.ofSeconds(45))
                        .setRetryOptions(
                                RetryOptions.newBuilder()
                                        // input.maxRetryAttempts() is retries AFTER the first try
                                        // (0-5); Temporal's
                                        // setMaximumAttempts is TOTAL attempts and treats a raw 0
                                        // as unlimited, so
                                        // +1 both converts the semantics and guarantees this is
                                        // never 0.
                                        .setMaximumAttempts(maxAttempts)
                                        .setInitialInterval(Duration.ofSeconds(2))
                                        .setBackoffCoefficient(2.0)
                                        .setMaximumInterval(Duration.ofSeconds(20))
                                        // A quota-exceeded rejection is deterministic within the
                                        // same request
                                        // burst - the daily/monthly cap doesn't clear in the 2-20s
                                        // backoff window,
                                        // so retrying just burns more attempts (and their own
                                        // reservation-then-rollback token cost) for a guaranteed
                                        // identical
                                        // failure. Fail once, fast, instead. Temporal matches
                                        // failure types by
                                        // exact class name, so every TenantLimitExceededException
                                        // subclass that
                                        // can surface here is listed.
                                        .setDoNotRetry(
                                                TenantLimitExceededException.class.getName(),
                                                com.agenticai.core.agents.spendcap
                                                        .SpendCapReachedException.class
                                                        .getName())
                                        .build())
                        .build());
    }

    @Override
    public void approvalDecision(WorkflowApprovalSignal signal) {
        // Just park the payload - the run() coroutine picks it up out of
        // Workflow.await. Signals deliver in send order and the executor
        // CAS-guards decisions, so at most one is ever pending per run.
        this.pendingApproval = signal;
    }

    /**
     * Blocks this workflow's coroutine until an {@code approvalDecision} signal for {@code nodeId}
     * arrives or {@link #APPROVAL_WAIT_TIMEOUT} elapses (returns null). A signal addressed to a
     * DIFFERENT node is consumed and ignored with a warning - it can only be a stale or misrouted
     * decision (the executor's run-level CAS means at most one real decision exists per run), and
     * letting it satisfy this wait would resume the wrong node under an approval nobody granted it.
     */
    private WorkflowApprovalSignal awaitApprovalDecision(String runId, String nodeId) {
        while (true) {
            boolean decided = Workflow.await(APPROVAL_WAIT_TIMEOUT, () -> pendingApproval != null);
            if (!decided) {
                return null;
            }
            WorkflowApprovalSignal signal = pendingApproval;
            pendingApproval = null;
            if (nodeId.equals(signal.nodeId())) {
                return signal;
            }
            log.warn(
                    "Ignoring approval signal addressed to node {} while run {} is parked at node"
                            + " {}.",
                    signal.nodeId(),
                    runId,
                    nodeId);
        }
    }
}
