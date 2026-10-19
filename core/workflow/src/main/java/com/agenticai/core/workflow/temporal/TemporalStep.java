package com.agenticai.core.workflow.temporal;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;

/**
 * One container construct of the durable traversal (loop, parallel, fan_out), the Temporal counterpart of the
 * in-process engine's container steps. Deterministic workflow code only - see {@link TemporalRunState}.
 */
interface TemporalStep {

    /** Runs the construct for {@code node}, which received {@code currentOutput}. */
    Outcome run(TemporalRunState state, WorkflowNodeDto node, String currentOutput);

    /** {@code finished != null}: the execution ends with that result. Otherwise continue at {@code nextNodeId}. */
    record Outcome(WorkflowRunResult finished, String nextNodeId, String nextOutput) {

        static Outcome finished(WorkflowRunResult result) {
            return new Outcome(result, null, null);
        }

        static Outcome next(String nodeId, String output) {
            return new Outcome(null, nodeId, output);
        }
    }
}
