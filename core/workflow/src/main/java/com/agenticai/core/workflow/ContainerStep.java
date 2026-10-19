package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;

/**
 * One graph construct that the traversal loop cannot hand to {@link WorkflowNodeExecutor#executeNode} because it
 * needs the graph's adjacency or the run's park/finalise machinery: loop, parallel, fan_out, sub_flow and wait.
 * Each is its own class so the engine only orchestrates.
 */
interface ContainerStep {

    /** The node type this step handles, compared case-insensitively. */
    String nodeType();

    /**
     * Runs the construct for {@code node}, which received {@code currentOutput}. Returns either the run, finished
     * ({@link Outcome#finished}), or where the traversal continues ({@link Outcome#next}).
     */
    Outcome run(RunTraversalState state, WorkflowNodeDto node, String currentOutput);

    /** {@code finished != null}: the leg ended and this is the run. Otherwise continue at {@code nextNodeId}. */
    record Outcome(WorkflowRun finished, String nextNodeId, String nextOutput) {

        static Outcome finished(WorkflowRun run) {
            return new Outcome(run, null, null);
        }

        static Outcome next(String nodeId, String output) {
            return new Outcome(null, nodeId, output);
        }
    }
}
