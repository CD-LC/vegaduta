package com.agenticai.core.workflow.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Every side effect a {@link WorkflowRunWorkflowImpl} execution performs -
 * agent/tool/channel calls (via {@link #executeNode}) and run-row/event
 * persistence (via {@link #recordProgress}) - lives behind this interface.
 * Temporal retries a failed Activity invocation automatically per the {@code
 * RetryOptions} {@code WorkflowRunWorkflowImpl} configures on its activity
 * stub, and re-attaches to an in-flight Activity after a worker
 * crash/restart instead of re-running it from scratch once it has actually
 * started - this is the mechanism that gives the platform its new "kill the
 * worker mid-run, it resumes instead of restarting" guarantee (Stage 3's
 * Definition of Done).
 */
@ActivityInterface
public interface WorkflowNodeActivities {

    @ActivityMethod
    NodeExecutionActivityResult executeNode(NodeExecutionActivityInput input);

    @ActivityMethod
    void recordProgress(RunProgressInput input);

    /**
     * Writes a container node's (loop / parallel / fan_out) merged output to the run data store so a later
     * {@code $node["<label>"]} reads it. An activity because workflow code must not touch the database.
     */
    @ActivityMethod
    void recordContainerOutput(ContainerOutputInput input);
}
