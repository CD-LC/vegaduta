package com.agenticai.core.workflow.temporal;

import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.OrchestratorService;
import com.agenticai.core.workflow.RunMode;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunRepository;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Drop-in replacement for {@code WorkflowExecutionEngine} - same external contract ({@code
 * execute(...)} returns a {@link WorkflowRun} synchronously, once the run is done), durable
 * crash-resumable execution underneath via {@link WorkflowRunWorkflow}. {@code WorkflowService}
 * picks between this and the legacy engine based on {@code app.workflow.execution-engine} (see that
 * class) - the canvas UI, REST contract, and every downstream consumer of {@link WorkflowRun} stay
 * completely unaware of which one actually ran.
 *
 * <p>Run-row creation and the initial "workflow.run.started" event stay here, on the ordinary
 * request thread, exactly like the legacy engine does it (real {@link TenantContext} already valid,
 * no special handling needed) - only the per-node work and per-node/terminal persistence move into
 * the Temporal workflow + its activities, since those are the parts that actually benefit from
 * durability.
 *
 * <p><b>Approval-resume is signal-based</b> (v1): a run whose node halts AWAITING_APPROVAL stays
 * ALIVE inside its Temporal execution, parked on {@code Workflow.await} - the run row reads
 * AWAITING_APPROVAL exactly as the legacy engine persisted it, but nothing about the execution is
 * lost. {@code WorkflowApprovalActionExecutor} CAS-claims the row (unchanged claim semantics) and
 * then calls {@link #signalApprovalDecision}, which delivers the decision as a Temporal signal to
 * the parked workflow; the workflow re-executes the approved node with the replay grant carried on
 * the activity input. Executions recorded before this path existed end on halt as before ({@code
 * Workflow.getVersion} guard inside the workflow), and their approvals transparently fall back to
 * the in-process resume - see {@code WorkflowApprovalActionExecutor}'s signal-failure fallback.
 */
@Component
@Profile("temporal")
public class TemporalWorkflowExecutionEngine {

    private static final Logger log =
            LoggerFactory.getLogger(TemporalWorkflowExecutionEngine.class);

    private final WorkflowRunRepository runRepository;
    private final OrchestratorService orchestratorService;
    private final WorkflowEventPublisher eventPublisher;
    private final WorkflowClient workflowClient;
    private final ObjectProvider<com.agenticai.core.workflow.version.WorkflowVersionService>
            versionService;

    @Value("${app.workflow.temporal.run-timeout-seconds:600}")
    private long runTimeoutSeconds;

    @Autowired // two public ctors: without this Spring can't pick one and dies with "no default
    // constructor"
    public TemporalWorkflowExecutionEngine(
            WorkflowRunRepository runRepository,
            OrchestratorService orchestratorService,
            WorkflowEventPublisher eventPublisher,
            WorkflowClient workflowClient,
            ObjectProvider<com.agenticai.core.workflow.version.WorkflowVersionService>
                    versionService) {
        this.runRepository = runRepository;
        this.orchestratorService = orchestratorService;
        this.eventPublisher = eventPublisher;
        this.workflowClient = workflowClient;
        this.versionService = versionService;
    }

    public TemporalWorkflowExecutionEngine(
            WorkflowRunRepository runRepository,
            OrchestratorService orchestratorService,
            WorkflowEventPublisher eventPublisher,
            WorkflowClient workflowClient) {
        this(runRepository, orchestratorService, eventPublisher, workflowClient, null);
    }

    public WorkflowRun execute(
            WorkflowDefinition definition,
            WorkflowGraphDto graph,
            String startNodeId,
            String input,
            String requestedBy,
            UUID clientRunId) {
        return execute(
                definition,
                graph,
                startNodeId,
                input,
                requestedBy,
                clientRunId,
                RunMode.PRODUCTION);
    }

    /**
     * As above in an explicit {@link RunMode}. DRAFT runs the caller's (live) graph instead of the
     * PRODUCTION snapshot and carries the mode into the workflow input, which stamps it on every
     * node activity. Every overload without a mode is PRODUCTION.
     */
    public WorkflowRun execute(
            WorkflowDefinition definition,
            WorkflowGraphDto graph,
            String startNodeId,
            String input,
            String requestedBy,
            UUID clientRunId,
            RunMode mode) {
        return execute(definition, graph, startNodeId, input, requestedBy, clientRunId, mode, null);
    }

    /** As above, recording the run's optional operator comment on the row. */
    public WorkflowRun execute(
            WorkflowDefinition definition,
            WorkflowGraphDto graph,
            String startNodeId,
            String input,
            String requestedBy,
            UUID clientRunId,
            RunMode mode,
            String comment) {
        if (mode == null) {
            mode = RunMode.PRODUCTION;
        }
        // C1 pin: when a PRODUCTION label exists the run executes THAT
        // snapshot's graph, not whatever the caller loaded off the live
        // row - the same rule WorkflowService#resolveRunGraph gives the
        // legacy engine. The resolved ordinal is stamped on the run row AND
        // carried into WorkflowRunInput, so the Temporal execution's own
        // history records which snapshot is replaying and a mid-run promote
        // can never swap the graph under a live workflow.
        WorkflowGraphDto effectiveGraph = graph;
        Integer pinnedVersion = null;
        com.agenticai.core.workflow.version.WorkflowVersionService versions =
                versionService == null ? null : versionService.getIfAvailable();
        if (mode.honoursPins()) {
            // A draft run executes the live graph the caller loaded; the stamped ordinal is the
            // newest snapshot,
            // which by convention describes the live config.
            pinnedVersion =
                    versions == null ? null : versions.currentVersionNumber(definition.getId());
        } else if (versions != null) {
            java.util.Optional<com.agenticai.core.workflow.version.WorkflowVersion> resolved =
                    versions.resolveRunVersion(definition.getId());
            if (resolved.isPresent()) {
                pinnedVersion = resolved.get().getVersionNumber();
                try {
                    effectiveGraph =
                            new tools.jackson.databind.ObjectMapper()
                                    .readValue(
                                            resolved.get().getGraphJson(), WorkflowGraphDto.class);
                } catch (Exception e) {
                    log.warn(
                            "Workflow {}: pinned version v{} graph is undecodable - running the caller's graph",
                            definition.getId(),
                            pinnedVersion,
                            e);
                }
            }
        }

        orchestratorService.validate(effectiveGraph, definition.getId());

        UUID runId = clientRunId != null ? clientRunId : UUID.randomUUID();
        WorkflowRun run =
                new WorkflowRun(runId, definition.getId(), definition.getName(), requestedBy);
        run.setWorkflowVersion(pinnedVersion);
        run.setRunMode(mode);
        run.setComment(comment);
        runRepository.save(run);

        eventPublisher.publish(
                "workflow.run.started",
                runId.toString(),
                Map.of(
                        "workflowId",
                        definition.getId().toString(),
                        "workflowName",
                        definition.getName()));

        Optional<TenantContext> tenantContext = TenantContext.current();
        WorkflowRunInput workflowInput =
                new WorkflowRunInput(
                        runId.toString(),
                        definition.getId().toString(),
                        definition.getName(),
                        effectiveGraph,
                        startNodeId,
                        input,
                        requestedBy,
                        tenantContext.map(TenantContext::getTenantId).orElse(null),
                        tenantContext.map(TenantContext::getSubject).orElse(null),
                        tenantContext.map(TenantContext::getRoles).orElse(Set.of()),
                        definition.getMaxStepsPerRun(),
                        definition.getStepTimeoutSeconds(),
                        definition.getMaxRetryAttempts(),
                        pinnedVersion,
                        mode.wire());

        // Deterministic Workflow Id from runId (not a random one) - this is
        // what makes re-submitting the same runId idempotent at the Temporal
        // layer: a duplicate start attaches to the already-running/completed
        // execution instead of creating a second one.
        WorkflowOptions options =
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TemporalConfig.TASK_QUEUE)
                        .setWorkflowId("workflow-run-" + runId)
                        .build();
        WorkflowRunWorkflow workflowStub =
                workflowClient.newWorkflowStub(WorkflowRunWorkflow.class, options);

        try {
            WorkflowClient.start(workflowStub::run, workflowInput);
            WorkflowStub.fromTyped(workflowStub)
                    .getResult(runTimeoutSeconds, TimeUnit.SECONDS, WorkflowRunResult.class);
        } catch (TimeoutException e) {
            // The Temporal workflow execution is still genuinely in progress
            // durably on the server - this HTTP request just isn't going to
            // wait any longer for it. GET .../runs/{runId} will keep showing
            // RUNNING with a growing trace (progressActivities.recordProgress
            // keeps writing to the same row) until it actually finishes.
            log.info(
                    "Workflow run {} for '{}' is still running after {}s - returning current state, "
                            + "the run continues in the background.",
                    runId,
                    definition.getName(),
                    runTimeoutSeconds);
        } catch (RuntimeException e) {
            log.warn(
                    "Workflow run {} for '{}' - Temporal execution reported a failure: {}",
                    runId,
                    definition.getName(),
                    e.getMessage());
        }

        // The activities already wrote the authoritative row (trace, status,
        // file output) - re-read it rather than trusting WorkflowRunResult
        // alone, same "the DB row is the source of truth" posture the legacy
        // engine's own return value has.
        return runRepository.findById(runId).orElse(run);
    }

    /**
     * Stop Run - Temporal's own client-initiated cancellation
     * (https://docs.temporal.io/develop/java/cancellation), the idiomatic fit for this durable,
     * possibly-cross-worker execution path rather than reusing {@link
     * com.agenticai.core.workflow.WorkflowRunCancellationService}'s Redis flag the way the
     * synchronous {@code WorkflowExecutionEngine} does (that class's own javadoc explains why: a
     * Temporal Workflow method would have to make an Activity call just to poll Redis, since
     * Workflow code must stay side-effect-free - more machinery for a signal this SDK already
     * delivers natively).
     *
     * <p>{@code newUntypedWorkflowStub(String)} targets the SAME deterministic workflow id {@link
     * #execute} constructs ({@code "workflow-run-" + runId}), so this reaches the exact execution a
     * client-generated {@code runId} started without this class needing to track the typed
     * stub/options it used at start time. Cancellation requests are delivered to whatever Activity
     * call is currently in-flight in {@link WorkflowRunWorkflowImpl#run} as a {@code
     * CanceledFailure} (caught there, see that class's updated catch block) - note this is NOT the
     * same "only between node executions" boundary the synchronous engine's Redis-flag check gives:
     * Temporal's default activity cancellation type ({@code TRY_CANCEL}) unblocks the Workflow's
     * WAIT on an in-flight node's activity call promptly, without waiting for that activity to
     * finish - but since none of {@code WorkflowNodeActivities}' calls heartbeat, the underlying
     * node execution already dispatched to the worker (an in-progress LLM call, tool call, output
     * send) is not forcibly killed, it simply keeps running to completion in the background with
     * its result discarded. Functionally the same honest limitation the synchronous path documents
     * ("an in-flight node isn't interrupted"), just via a different mechanism - not a stronger
     * guarantee.
     *
     * <p>Swallows "this workflow execution doesn't exist / already closed" failures as a no-op -
     * {@code WorkflowService#cancelRun} already short-circuits on a non-RUNNING run before ever
     * reaching here, but a genuine race (the run finished on its own between that check and this
     * call) should read as "nothing left to cancel," not an error.
     */
    public void cancelRun(UUID runId) {
        try {
            workflowClient.newUntypedWorkflowStub("workflow-run-" + runId).cancel();
        } catch (RuntimeException e) {
            log.info(
                    "Cancel requested for workflow run {} but its Temporal execution could not be reached "
                            + "(likely already finished) - treating as a no-op: {}",
                    runId,
                    e.getMessage());
        }
    }

    /**
     * Delivers a human approval decision to a run parked inside a live Temporal workflow (the
     * signal-resume path - see the class javadoc). Reconstructs the typed stub from the
     * deterministic workflow id {@link #execute} uses, so no client-side bookkeeping is needed.
     * This is a fire-and-forget signal - the parked workflow wakes on its own schedule - so a run
     * whose Temporal execution already ended throws (WorkflowNotFoundException) and the caller
     * falls back to the legacy in-process resume, which is exactly what every pre-signal run needs.
     */
    public void signalApprovalDecision(
            UUID runId, String nodeId, boolean approved, String decidedBy, String reason) {
        workflowClient
                .newWorkflowStub(WorkflowRunWorkflow.class, "workflow-run-" + runId)
                .approvalDecision(new WorkflowApprovalSignal(nodeId, approved, decidedBy, reason));
    }
}
