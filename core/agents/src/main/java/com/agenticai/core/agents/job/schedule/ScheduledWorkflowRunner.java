package com.agenticai.core.agents.job.schedule;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * SPI through which an agent schedule starts a WORKFLOW run. {@code core/agents}
 * cannot depend on {@code core/workflow} (the dependency runs the other way), so
 * the workflow module supplies the implementation and this module only sees the
 * interface.
 *
 * <p>Both methods are called from the schedule tick, which must only ENQUEUE:
 * {@link #submit} therefore hands the run to a bounded executor and returns
 * immediately, and the outcome arrives later through the callback.
 */
public interface ScheduledWorkflowRunner {

    /** Whether the workflow still exists in the current tenant schema. */
    boolean exists(UUID workflowId);

    /**
     * Starts the workflow asynchronously as {@code requestedBy}.
     *
     * @param runId pre-allocated run id (also the workflow's client run id)
     * @param onFinished called exactly once with ({@code "SUCCEEDED"|"FAILED"}, error-or-null),
     *     on the worker thread with the tenant context restored
     * @throws java.util.concurrent.RejectedExecutionException when the bounded pool is full
     */
    void submit(UUID workflowId, UUID runId, String input, String requestedBy,
                BiConsumer<String, String> onFinished);
}
