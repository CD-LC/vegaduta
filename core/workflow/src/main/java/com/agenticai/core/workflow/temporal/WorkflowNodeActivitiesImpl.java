package com.agenticai.core.workflow.temporal;

import com.agenticai.core.approval.ApprovalReplayGrant;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.ExpressionScopeContext;
import com.agenticai.core.workflow.WorkflowIntelligenceSettings;
import com.agenticai.core.workflow.WorkflowNodeExecutor;
import com.agenticai.core.workflow.WorkflowRun;
import com.agenticai.core.workflow.WorkflowRunRepository;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Spring-managed implementation of {@link WorkflowNodeActivities}, registered with the Temporal
 * {@code Worker} in {@code TemporalConfig}. {@code @Profile("temporal")}: like every other class in
 * this package, simply doesn't exist as a bean unless the "temporal" profile is active - matches
 * this codebase's standing "optional feature = @Profile-gated, zero behavior change by default"
 * convention (see {@code WorkflowKafkaConfig}).
 *
 * <p><b>Tenant context, explicitly, every call</b>: a Temporal Activity method runs on one of the
 * Worker's own thread-pool threads, which has never seen a {@link TenantContext#set} call from the
 * request thread that originally triggered this workflow - the exact same class of bug task #63
 * fixed for reactive tool-call threads (`TenantContextPropagatingToolCallback`), just with a
 * different scheduler underneath. The fix is the same pattern: the caller
 * (`TemporalWorkflowExecutionEngine`) captures the real {@code TenantContext} once, on the correct
 * request thread, and threads it through as plain data ({@code tenantId}/{@code subject}/{@code
 * roles} on both {@link NodeExecutionActivityInput} and {@link RunProgressInput}) all the way down
 * to here, where it's set explicitly at the top of every method and restored/cleared in a {@code
 * finally} block - never inherited implicitly.
 *
 * <p><b>Heartbeats, and why {@code executeNode} farms the real work out</b>: a Temporal Activity
 * method itself must not block for the whole duration of a long node (an LLM round-trip plus tool
 * calls routinely exceeds 60s) - without periodic {@link ActivityExecutionContext#heartbeat} calls,
 * Stop Run is only observed BETWEEN nodes (the workflow's cancel request reaches the blocked
 * activity call but nothing inside the node ever checks it) and a silently dead worker would only
 * surface when the full start-to-close timeout elapsed. So {@code executeNode} dispatches the
 * actual {@link WorkflowNodeExecutor} call onto {@code temporalActivityExecutor} and spends its own
 * turn heartbeating every {@code app.workflow.temporal.activity-heartbeat-seconds} until the node
 * finishes; a heartbeat that throws ({@code ActivityNotExistsException} / worker shutdown) means
 * this activity was cancelled - the node thread is then interrupted so an in-flight LLM/tool call
 * actually stops rather than finishing orphaned and discarded anyway.
 *
 * <p><b>Approval replay across the durable boundary</b>: on the signal-resume path (see {@code
 * WorkflowRunWorkflowImpl}'s await-approval branch) the approved action type arrives serialized on
 * the input as {@code approvalReplayActionType} and is re-established as a thread-local {@link
 * ApprovalReplayGrant} on the node worker thread - the ThreadLocal cannot cross the
 * workflow/activity boundary on its own, exactly like {@code TenantContext} can't.
 */
@Component
@Profile("temporal")
public class WorkflowNodeActivitiesImpl implements WorkflowNodeActivities {

    private final WorkflowNodeExecutor nodeExecutor;
    private final WorkflowRunRepository runRepository;
    private final WorkflowEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ExecutorService activityExecutor;

    @Value("${app.workflow.temporal.activity-heartbeat-seconds:10}")
    private long heartbeatIntervalSeconds;

    public WorkflowNodeActivitiesImpl(
            WorkflowNodeExecutor nodeExecutor,
            WorkflowRunRepository runRepository,
            WorkflowEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            @Qualifier("temporalActivityExecutor") ExecutorService activityExecutor) {
        this.nodeExecutor = nodeExecutor;
        this.runRepository = runRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.activityExecutor = activityExecutor;
    }

    @Override
    public NodeExecutionActivityResult executeNode(NodeExecutionActivityInput input) {
        ActivityExecutionContext context = Activity.getExecutionContext();
        CompletableFuture<NodeExecutionActivityResult> work =
                CompletableFuture.supplyAsync(
                        () ->
                                withTenantContext(
                                        input.tenantId(),
                                        input.subject(),
                                        input.roles(),
                                        () -> {
                                            // The replay grant must be established on the SAME
                                            // thread
                                            // that calls nodeExecutor.executeNode - its gate
                                            // consumes
                                            // the grant via ThreadLocal - so it happens here inside
                                            // the
                                            // worker-thread body, not on the Temporal activity
                                            // thread.
                                            String replayGrant = input.approvalReplayActionType();
                                            if (replayGrant != null) {
                                                ApprovalReplayGrant.grant(replayGrant);
                                            }
                                            // Loop / fan_out facts for $item/$index/$items, bound
                                            // on this same worker thread.
                                            ExpressionScopeContext.bind(input.expressionScope());
                                            try {
                                                long startedAt = Instant.now().toEpochMilli();
                                                WorkflowNodeExecutor.NodeExecutionResult result =
                                                        nodeExecutor.executeNode(
                                                                input.workflowDefinitionId(),
                                                                input.workflowDefinitionName(),
                                                                input.runId(),
                                                                input.node(),
                                                                input.input(),
                                                                input.requestedBy(),
                                                                input.intelligenceSettings() != null
                                                                        ? input
                                                                                .intelligenceSettings()
                                                                        : WorkflowIntelligenceSettings
                                                                                .defaults());
                                                long completedAt = Instant.now().toEpochMilli();
                                                return new NodeExecutionActivityResult(
                                                        result.output(),
                                                        result.status(),
                                                        result.summary(),
                                                        result.branch(),
                                                        result.haltRun() != null
                                                                ? result.haltRun().name()
                                                                : null,
                                                        result.logLines(),
                                                        result.fileName(),
                                                        result.fileData(),
                                                        startedAt,
                                                        completedAt);
                                            } finally {
                                                ApprovalReplayGrant.clear();
                                                ExpressionScopeContext.clear();
                                            }
                                        }),
                        activityExecutor);

        while (true) {
            try {
                return work.get(heartbeatIntervalSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException stillRunning) {
                try {
                    context.heartbeat("node " + input.node().id() + " still executing");
                } catch (RuntimeException cancelledOrShuttingDown) {
                    // ActivityNotExistsException (Stop Run / TRY_CANCEL) or
                    // ActivityWorkerShutdownException - interrupt the node
                    // thread so the in-flight LLM/tool/output call actually
                    // stops instead of finishing orphaned.
                    work.cancel(true);
                    throw cancelledOrShuttingDown;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                work.cancel(true);
                throw new RuntimeException(
                        "Interrupted while awaiting node " + input.node().id() + " execution",
                        interrupted);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new RuntimeException(cause);
            }
        }
    }

    @Override
    public void recordProgress(RunProgressInput input) {
        withTenantContext(
                input.tenantId(),
                input.subject(),
                input.roles(),
                () -> {
                    WorkflowRun run =
                            runRepository
                                    .findById(java.util.UUID.fromString(input.runId()))
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "workflow_run row "
                                                                    + input.runId()
                                                                    + " is missing - TemporalWorkflowExecutionEngine "
                                                                    + "must create it before starting the Temporal workflow."));
                    String traceJson = writeTrace(input.trace());
                    if (input.terminal()) {
                        WorkflowRunStatus status = WorkflowRunStatus.valueOf(input.status());
                        run.complete(
                                status,
                                traceJson,
                                input.errorMessage(),
                                input.outputFileName(),
                                input.outputData());
                        runRepository.save(run);
                        eventPublisher.publish(
                                "workflow.run." + status.name().toLowerCase(java.util.Locale.ROOT),
                                input.runId(),
                                Map.of("workflowId", input.workflowDefinitionId()));
                    } else {
                        run.updateTrace(traceJson);
                        runRepository.save(run);
                        eventPublisher.publish(
                                "workflow.node.completed",
                                input.runId(),
                                Map.of(
                                        "nodeId",
                                        String.valueOf(input.lastNodeId()),
                                        "nodeType",
                                        String.valueOf(input.lastNodeType()),
                                        "status",
                                        input.status()));
                    }
                    return null;
                });
    }

    @Override
    public void recordContainerOutput(ContainerOutputInput input) {
        withTenantContext(
                input.tenantId(),
                input.subject(),
                input.roles(),
                () -> {
                    nodeExecutor.recordContainerOutput(
                            input.workflowDefinitionId(),
                            input.workflowDefinitionName(),
                            input.runId(),
                            input.requestedBy(),
                            input.node(),
                            input.output());
                    return null;
                });
    }

    private String writeTrace(Object trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Restores whatever context was already on this worker thread afterward (rather than
     * unconditionally clearing) - same defensive pattern {@code
     * TenantContextPropagatingToolCallback} uses, even though in practice a fresh Temporal worker
     * thread should never have one set already.
     */
    private <T> T withTenantContext(
            String tenantId,
            String subject,
            Set<String> roles,
            java.util.function.Supplier<T> body) {
        Optional<TenantContext> previous = TenantContext.current();
        if (tenantId != null) {
            TenantContext.set(
                    TenantContext.of(tenantId, subject, roles != null ? roles : Set.of()));
        }
        try {
            return body.get();
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }
}
