package com.agenticai.core.workflow;

import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestRepository;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.json.JsonNodes;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.temporal.TemporalWorkflowExecutionEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The remaining half of F-05: turns a human decision on a {@code
 * "workflow.node.<type>"}-family {@link ApprovalRequest} into an actual
 * change to the halted {@link WorkflowRun}, closing the gap {@link
 * WorkflowRunStatus}'s javadoc stated honestly for Phase 1 ("approving the
 * request today marks it decided... but does not yet re-enter the graph at
 * the next node"). One family executor for every {@code workflow.node.*}
 * action type - the node type is only known at runtime - found by {@code
 * ApprovalActionExecutorRegistry#find}'s {@code supports()} fallback, the
 * exact registration pattern {@code AgentToolCallApprovalExecutor}
 * established for {@code mcp_tool.*}.
 *
 * <p><b>Approve</b> ({@link #execute}): atomically claims the run
 * (AWAITING_APPROVAL &rarr; RUNNING via {@link
 * WorkflowRunRepository#compareAndSetStatus} - see that method for why a
 * guarded UPDATE is the double-resume mechanism) and re-enters {@link
 * WorkflowExecutionEngine#resume} at the approved node, under an {@link
 * ApprovalReplayGrant} so the node's own approval gate lets the
 * already-approved execution through instead of halting again. Completed
 * nodes never re-execute: the node's input comes verbatim from the payload's
 * resume envelope (recorded at halt time by {@code
 * OrchestratorService#authorizeSensitiveNode}), and the persisted trace is
 * carried forward as history. The resumed traversal runs under the RECORDED
 * tenant context (save-in-a-local/restore-in-finally, same idiom as {@code
 * AgentToolCallApprovalExecutor}) and continues attributed to the run's
 * original {@code requestedBy}, not the deciding admin.
 *
 * <p><b>Reject</b> ({@link #onRejected}): previously a rejection only marked
 * the request row REJECTED and the run sat AWAITING_APPROVAL forever - now
 * the run is atomically moved to FAILED with the decider and reason on both
 * the {@code errorMessage} and a terminal trace entry. No platform issue is
 * recorded for a rejection: a human saying "no" is governance working, not a
 * defect to triage. Best-effort by contract - {@code
 * HumanApprovalGateService#reject} must never fail the decision itself over
 * run bookkeeping.
 *
 * <p><b>Two resume paths, picked automatically</b>: if the {@code temporal}
 * engine is active, {@link #execute} first signals the run's live Temporal
 * workflow ({@code TemporalWorkflowExecutionEngine#signalApprovalDecision})
 * - runs started since the await-approval-resume change park inside their
 * execution and simply wake up, keeping Temporal's durability for the whole
 * resumed leg. A signal that can't be delivered (the execution already
 * returned - every pre-signal run, and every legacy-engine run, looks like
 * that) falls back to the in-process {@code WorkflowExecutionEngine#resume}
 * exactly as before, so nothing about the old behavior is lost and no
 * run can get stuck between the two paths.
 *
 * <p><b>Honest limits:</b> the legacy fallback runs in-process on the
 * deciding thread with the synchronous engine's durability. Requests
 * recorded before the resume envelope existed (or whose node input exceeded
 * {@code OrchestratorService#MAX_RECORDED_NODE_INPUT_CHARS}) cannot resume
 * via the legacy path and fail with an explicit message - the signal path
 * does not need the envelope (the parked workflow still holds its own
 * node input), so it is attempted first and only its failure consults the
 * envelope. The decision itself still records normally either way.
 */
@Component
public class WorkflowApprovalActionExecutor implements ApprovalActionExecutor {

    /** Must stay in sync with {@code OrchestratorService#authorizeSensitiveNode}'s action-type construction. */
    public static final String ACTION_TYPE_PREFIX = "workflow.node.";

    private static final Logger log = LoggerFactory.getLogger(WorkflowApprovalActionExecutor.class);

    // ObjectProvider is load-bearing, not a convenience: a direct engine
    // field is a genuine bean cycle (this bean -> WorkflowExecutionEngine ->
    // WorkflowNodeExecutor -> OrchestratorService -> HumanApprovalGateService
    // -> ApprovalActionExecutorRegistry -> List<ApprovalActionExecutor> ->
    // this bean) - the same cycle, broken the same way, as
    // AgentToolCallApprovalExecutor's AgentToolProvider field.
    private final ObjectProvider<WorkflowExecutionEngine> executionEngine;
    // Same ObjectProvider trick as executionEngine (the bean simply doesn't
    // exist without the "temporal" profile) - plus this one must tolerate
    // ABSENT, not just lazily resolve: non-temporal deployments never get
    // the signal path at all.
    private final ObjectProvider<TemporalWorkflowExecutionEngine> temporalEngine;
    private final WorkflowRunRepository runRepository;
    private final WorkflowDefinitionRepository definitionRepository;
    private final ApprovalRequestRepository requestRepository;
    private final WorkflowEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public WorkflowApprovalActionExecutor(
        ObjectProvider<WorkflowExecutionEngine> executionEngine,
        ObjectProvider<TemporalWorkflowExecutionEngine> temporalEngine,
        WorkflowRunRepository runRepository,
        WorkflowDefinitionRepository definitionRepository,
        ApprovalRequestRepository requestRepository,
        WorkflowEventPublisher eventPublisher,
        ObjectMapper objectMapper
    ) {
        this.executionEngine = executionEngine;
        this.temporalEngine = temporalEngine;
        this.runRepository = runRepository;
        this.definitionRepository = definitionRepository;
        this.requestRepository = requestRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * The registry's uniqueness key, NOT a real action type - no request is
     * ever recorded as bare {@code "workflow.node."}; {@link #supports}
     * routes the real {@code workflow.node.<type>} requests here via the
     * registry's exact-match-first fallback.
     */
    @Override
    public String actionType() {
        return ACTION_TYPE_PREFIX;
    }

    @Override
    public boolean supports(String actionType) {
        return actionType != null
            && actionType.startsWith(ACTION_TYPE_PREFIX)
            && actionType.length() > ACTION_TYPE_PREFIX.length();
    }

    @Override
    public void execute(ApprovalRequest request) {
        ResumeTarget target = resolveTarget(request, true);
        Optional<TenantContext> previous = TenantContext.current();
        if (target.tenantId() != null) {
            TenantContext.set(TenantContext.of(target.tenantId(), resumeSubject(target), Set.of()));
        }
        try {
            WorkflowRun run = runRepository.findById(target.runId()).orElseThrow(() ->
                new WorkflowApprovalResumeException("Approval request " + request.getId()
                    + " references workflow run " + target.runId() + ", which no longer exists - cannot resume."));
            if (run.getStatus() != WorkflowRunStatus.AWAITING_APPROVAL) {
                // Includes the double-resume fast path and a retry-job pass
                // over a request whose run already reached a terminal state -
                // deliberately a successful no-op, not an error: the human's
                // decision stands; there is just nothing left to resume.
                log.info("Workflow run {} is {} (not AWAITING_APPROVAL) - approval request {} resumes nothing.",
                    run.getId(), run.getStatus(), request.getId());
                persistExecutionResult(request, "Run " + run.getId() + " was already " + run.getStatus()
                    + " - nothing to resume.");
                return;
            }
            int claimed = runRepository.compareAndSetStatus(
                target.runId(), WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.RUNNING);
            if (claimed == 0) {
                // Lost the CAS race to a concurrent decision - see
                // compareAndSetStatus's javadoc. The winner owns the resume.
                log.info("Workflow run {} was claimed by a concurrent decision - approval request {} resumes nothing.",
                    target.runId(), request.getId());
                persistExecutionResult(request, "Run " + target.runId()
                    + " was already claimed by a concurrent decision - nothing to resume.");
                return;
            }
            // Re-fetch AFTER the guarded update: the pre-CAS entity still
            // carries the halt-time errorMessage/completedAt, and the engine
            // saving that stale entity would resurrect them.
            WorkflowRun claimedRun = runRepository.findById(target.runId()).orElseThrow(() ->
                new WorkflowApprovalResumeException("Workflow run " + target.runId()
                    + " vanished between claim and resume."));
            // Signal-resume first (see class javadoc): a run parked inside a
            // live Temporal execution just needs the decision delivered -
            // the workflow re-executes the approved node itself, carrying
            // the replay grant on its next activity input. The signal runs
            // AFTER the CAS above, so the row claim still guards double
            // decisions and the parked workflow's run row already reads
            // RUNNING by the time it wakes.
            TemporalWorkflowExecutionEngine temporal = temporalEngine.getIfAvailable();
            if (temporal != null) {
                try {
                    temporal.signalApprovalDecision(
                        target.runId(), target.nodeId(), true, request.getDecidedBy(), null);
                    persistExecutionResult(request, "Approval for run " + target.runId() + " (node "
                        + target.nodeId() + ") delivered to the live Temporal execution - "
                        + "the run continues durably in the background.");
                    return;
                } catch (RuntimeException signalFailure) {
                    // A run whose Temporal execution already returned (every
                    // run recorded before the parked-workflow path existed,
                    // and every legacy-engine run) has nothing to signal -
                    // fall through to the in-process resume, which is the
                    // behavior those runs have always had.
                    log.info("Temporal approval signal for run {} was not deliverable ({}) - "
                        + "resuming in-process via the legacy engine.", target.runId(), signalFailure.getMessage());
                }
            }
            try {
                WorkflowRun finished = resumeRun(request, target, claimedRun);
                persistExecutionResult(request, "Run " + finished.getId() + " resumed at node " + target.nodeId()
                    + " and finished with status " + finished.getStatus()
                    + (finished.getErrorMessage() != null ? ": " + finished.getErrorMessage() : "."));
            } catch (RuntimeException e) {
                // The run was already claimed as RUNNING - leaving it that way
                // would strand it until WorkflowRunRecoveryJob reaps it hours
                // later with a misleading reason. Best-effort terminalize with
                // the real one, then rethrow so the gate records the failure.
                failClaimedRunBestEffort(claimedRun, e);
                throw e;
            }
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private WorkflowRun resumeRun(ApprovalRequest request, ResumeTarget target, WorkflowRun run) {
        WorkflowDefinition definition = definitionRepository.findById(run.getWorkflowId()).orElseThrow(() ->
            new WorkflowApprovalResumeException("Workflow " + run.getWorkflowId() + " behind run " + run.getId()
                + " no longer exists - cannot resume."));
        WorkflowGraphDto graph = readGraph(definition);
        List<NodeTraceEntry> trace = readTraceTolerantly(run);
        WorkflowExecutionEngine engine = executionEngine.getObject();
        // The grant is what lets the approved node itself back through
        // WorkflowNodeExecutor's approval gate (see that class). consume()
        // clears it at the gate; the finally covers a failure before the
        // gate ever runs, so no grant lingers on this pooled thread.
        ApprovalReplayGrant.grant(request.getActionType());
        try {
            // requestedBy: the run continues as its ORIGINAL requester - any
            // further gated node downstream should attribute (and self-
            // approval-check) against the person whose run this is, not the
            // admin who happened to decide this request.
            return engine.resume(definition, graph, run, trace, target.nodeId(), target.nodeInput(),
                run.getRequestedBy());
        } finally {
            ApprovalReplayGrant.clear();
        }
    }

    /**
     * Rejection now genuinely stops the run: AWAITING_APPROVAL &rarr; FAILED
     * through the same guarded CAS the approve path uses (so a rejection
     * racing an approval of a sibling request settles atomically - exactly
     * one decision wins the run), with the decider and reason recorded on
     * the run row and as a terminal trace entry. Quietly a no-op for rows
     * whose payload predates the runId-carrying shape - there is no run to
     * find - and for runs already past AWAITING_APPROVAL.
     */
    @Override
    public void onRejected(ApprovalRequest request, String decidedBy, String reason) {
        ResumeTarget target;
        try {
            target = resolveTarget(request, false);
        } catch (WorkflowApprovalResumeException e) {
            log.info("Rejected workflow approval request {} carries no resolvable run reference ({}) - "
                + "no run to fail.", request.getId(), e.getMessage());
            return;
        }
        Optional<TenantContext> previous = TenantContext.current();
        if (target.tenantId() != null) {
            TenantContext.set(TenantContext.of(target.tenantId(), resumeSubject(target), Set.of()));
        }
        try {
            int claimed = runRepository.compareAndSetStatus(
                target.runId(), WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.FAILED);
            if (claimed == 0) {
                log.info("Workflow run {} is no longer AWAITING_APPROVAL - rejection of request {} changes nothing.",
                    target.runId(), request.getId());
                return;
            }
            WorkflowRun run = runRepository.findById(target.runId()).orElse(null);
            if (run == null) {
                return;
            }
            String message = "Approval rejected by " + (decidedBy != null ? decidedBy : "unknown")
                + (reason != null && !reason.isBlank() ? ": " + reason : ".");
            List<NodeTraceEntry> trace = readTraceTolerantly(run);
            Instant now = Instant.now();
            // Node type recovered from the action type's suffix - the payload
            // doesn't carry it and parsing the whole graph for one label
            // would make rejection depend on the definition still existing.
            String nodeType = request.getActionType().substring(ACTION_TYPE_PREFIX.length());
            trace.add(new NodeTraceEntry(target.nodeId(), nodeType, "Human approval", "ERROR", message, now, now,
                List.of("The pending approval for this node was rejected; the run cannot continue.")));
            run.complete(WorkflowRunStatus.FAILED, writeTrace(trace, run), message,
                run.getOutputFileName(), run.getOutputData());
            runRepository.save(run);
            // No PlatformIssueService record on purpose - a human rejection
            // is the approval gate doing its job, not a platform defect.
            eventPublisher.publish("workflow.run.failed", run.getId().toString(),
                Map.of("workflowId", run.getWorkflowId().toString(), "error", message));
            // Wake a parked Temporal execution so it returns promptly rather
            // than sitting on Workflow.await until the wait timeout. The row
            // write above is already the authoritative outcome - the
            // workflow's reject branch deliberately does NOT rewrite it.
            TemporalWorkflowExecutionEngine temporal = temporalEngine.getIfAvailable();
            if (temporal != null) {
                try {
                    temporal.signalApprovalDecision(target.runId(), target.nodeId(), false, decidedBy, reason);
                } catch (RuntimeException signalFailure) {
                    log.info("Run {}'s Temporal execution could not be signaled the rejection ({}) - "
                        + "the terminal write above already settled it.", target.runId(), signalFailure.getMessage());
                }
            }
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    private void failClaimedRunBestEffort(WorkflowRun run, RuntimeException cause) {
        try {
            String message = "Resume after approval failed: "
                + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
            run.complete(WorkflowRunStatus.FAILED, run.getTraceJson(), message,
                run.getOutputFileName(), run.getOutputData());
            runRepository.save(run);
            eventPublisher.publish("workflow.run.failed", run.getId().toString(),
                Map.of("workflowId", run.getWorkflowId().toString(), "error", message));
        } catch (RuntimeException bookkeepingFailure) {
            log.warn("Failed to mark claimed workflow run {} FAILED after a resume failure - "
                + "WorkflowRunRecoveryJob will reap it.", run.getId(), bookkeepingFailure);
        }
    }

    private WorkflowGraphDto readGraph(WorkflowDefinition definition) {
        try {
            return objectMapper.readValue(definition.getGraphJson(), WorkflowGraphDto.class);
        } catch (Exception e) {
            throw new WorkflowApprovalResumeException("Workflow '" + definition.getName()
                + "' has an unreadable graph - cannot resume: " + e.getMessage(), e);
        }
    }

    /**
     * Tolerant on purpose: the prior trace is history/display, not resume
     * state (the resume input rides in the approval payload), so a corrupt
     * stored trace downgrades to a marker entry rather than blocking the
     * resume a human just asked for. The corrupt bytes are lost from display
     * on the next persist - an accepted trade, called out here rather than
     * hidden.
     */
    private List<NodeTraceEntry> readTraceTolerantly(WorkflowRun run) {
        if (run.getTraceJson() == null || run.getTraceJson().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(objectMapper.readValue(
                run.getTraceJson(), new TypeReference<List<NodeTraceEntry>>() { }));
        } catch (Exception e) {
            log.warn("Trace of workflow run {} is unparseable - resuming with a fresh trace.", run.getId());
            List<NodeTraceEntry> trace = new ArrayList<>();
            Instant now = Instant.now();
            trace.add(new NodeTraceEntry("trace-recovery", "engine", "Trace recovery", "SKIPPED",
                "The trace recorded before the approval halt could not be parsed and was replaced.", now, now,
                List.of()));
            return trace;
        }
    }

    private String writeTrace(List<NodeTraceEntry> trace, WorkflowRun run) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (Exception e) {
            // Same silent best-effort fallback shape as WorkflowExecutionEngine#writeTrace.
            return run.getTraceJson();
        }
    }

    private void persistExecutionResult(ApprovalRequest request, String result) {
        request.recordExecutionResult(result);
        requestRepository.save(request);
    }

    private static String resumeSubject(ResumeTarget target) {
        return target.subject() != null && !target.subject().isBlank()
            ? target.subject()
            : "system:workflow-approval-resume";
    }

    /** Everything a decision needs from the payload. Nullable: {@code tenantId}, {@code subject}, {@code nodeInput}. */
    record ResumeTarget(UUID runId, String nodeId, String tenantId, String subject, String nodeInput) {
    }

    /**
     * Payload shape (see {@code OrchestratorService#authorizeSensitiveNode}):
     * top-level {@code runId}/{@code nodeId} exist on every workflow-node
     * request ever recorded; the nested {@code "resume"} envelope ({@code
     * nodeInput}, {@code tenantId}, {@code subject}) only on requests
     * recorded since resume support landed. {@code requireInput} is the
     * approve/reject split: an approval without the recorded node input has
     * nothing correct to feed the resumed node and must decline loudly; a
     * rejection only needs the run id.
     */
    private ResumeTarget resolveTarget(ApprovalRequest request, boolean requireInput) {
        String payloadJson = request.getPayloadJson();
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new WorkflowApprovalResumeException(
                "Approval request " + request.getId() + " has no payload - cannot locate its workflow run.");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new WorkflowApprovalResumeException("Approval request " + request.getId()
                + " has an unparseable payload - cannot locate its workflow run: " + e.getMessage(), e);
        }
        UUID runId = parseUuid(payload.path("runId").asText(null));
        String nodeId = payload.path("nodeId").asText(null);
        if (runId == null || nodeId == null || nodeId.isBlank()) {
            throw new WorkflowApprovalResumeException("Approval request " + request.getId()
                + " does not identify a workflow run and node - cannot act on the run.");
        }
        JsonNode resume = payload.path("resume");
        if (requireInput && !resume.hasNonNull("nodeInput")) {
            throw new WorkflowApprovalResumeException("Approval request " + request.getId()
                + " was recorded without a resume envelope (it predates resume support, or the node's input "
                + "exceeded the recordable size) - the decision is recorded, but run " + runId
                + " cannot be resumed automatically. Re-run the workflow to continue.");
        }
        return new ResumeTarget(
            runId,
            nodeId,
            resume.path("tenantId").asText(null),
            resume.path("subject").asText(null),
            withFormValues(resume.path("nodeInput").asText(null), resume.path("formValues"))
        );
    }

    /**
     * A workflow threads ONE string from node to node, so the approver's answers travel the same
     * way: appended to the approval node's output as labelled lines the next node (usually an
     * agent) reads as part of its input.
     */
    static String withFormValues(String nodeInput, JsonNode formValues) {
        if (nodeInput == null || formValues == null || !formValues.isObject() || formValues.isEmpty()) {
            return nodeInput;
        }
        StringBuilder out = new StringBuilder(nodeInput).append("\n\nInformation provided by the approver:");
        JsonNodes.fields(formValues).forEachRemaining(e -> out.append("\n- ").append(e.getKey()).append(": ").append(e.getValue().asText()));
        return out.toString();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
