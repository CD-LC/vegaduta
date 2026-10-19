package com.agenticai.core.workflow;

import com.agenticai.core.agents.job.schedule.ScheduledWorkflowRunner;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lets an agent schedule (core/agents) start a workflow run without the agents
 * module depending on this one.
 *
 * <p>The schedule tick must only ENQUEUE, but {@link WorkflowService#run} is
 * synchronous and can make LLM calls, so the run is handed to a small bounded
 * pool of its own. A full queue throws {@link RejectedExecutionException} back to
 * the tick, which records it as a skip rather than blocking.
 *
 * <p>This deliberately calls {@code run(...)} and NOT {@link
 * WorkflowService#runScheduledWorkflow}: that method also advances the
 * workflow's OWN {@code schedule_cron}, which an agent schedule firing must not
 * do. The tenant context is re-established on the worker thread from the schema
 * captured at submit time, with the schedule owner's subject.
 */
@Component
public class AgentScheduleWorkflowRunner implements ScheduledWorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduleWorkflowRunner.class);
    private static final int POOL_SIZE = 2;
    private static final int QUEUE_CAPACITY = 16;

    private final WorkflowService workflowService;
    private final WorkflowDefinitionRepository definitionRepository;
    private final ThreadPoolExecutor executor;

    public AgentScheduleWorkflowRunner(
        WorkflowService workflowService, WorkflowDefinitionRepository definitionRepository
    ) {
        this.workflowService = workflowService;
        this.definitionRepository = definitionRepository;
        this.executor = TenantAwareExecutors.newThreadPool(
            POOL_SIZE, POOL_SIZE, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            TenantAwareExecutors.threadFactory("agent-schedule-workflow", true),
            new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public boolean exists(UUID workflowId) {
        return definitionRepository.existsById(workflowId);
    }

    @Override
    public void submit(
        UUID workflowId, UUID runId, String input, String requestedBy, BiConsumer<String, String> onFinished
    ) {
        String tenantSchema = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantSchema == null) {
            throw new IllegalStateException("A scheduled workflow run needs a tenant context");
        }
        executor.execute(() -> {
            TenantContext.set(TenantContext.of(tenantSchema, requestedBy, Set.of()));
            String status;
            String error = null;
            try {
                WorkflowRunResponse response = workflowService.run(workflowId, input, requestedBy, runId);
                WorkflowRunStatus s = response.status();
                if (s == WorkflowRunStatus.COMPLETED) {
                    status = "SUCCEEDED";
                } else if (s == WorkflowRunStatus.FAILED || s == WorkflowRunStatus.CANCELLED) {
                    status = "FAILED";
                    error = response.errorMessage() != null ? response.errorMessage() : "Run ended " + s;
                } else {
                    // e.g. AWAITING_APPROVAL: the run is parked, not failed.
                    status = "SUCCEEDED";
                    error = "Run ended in state " + s;
                }
            } catch (RuntimeException e) {
                log.warn("Scheduled workflow {} failed to run: {}", workflowId, e.getMessage());
                status = "FAILED";
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            try {
                onFinished.accept(status, error);
            } finally {
                TenantContext.clear();
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
