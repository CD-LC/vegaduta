package com.agenticai.core.workflow;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * "wait" parks the run durably and is handled by WorkflowExecutionEngine's traversal; reaching it
 * here (Temporal path, a loop body) must fail loudly rather than silently skip the wait.
 *
 * <p>"loop", "parallel" and "fan_out" are deliberately NOT registered: none is a single-node
 * operation, the engine special-cases them because only it holds the graph's adjacency.
 */
@Component
class WaitNodeHandler implements NodeHandler {

    @Override
    public Set<String> nodeTypes() {
        return Set.of("wait");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        throw new IllegalStateException(
                "Wait node '"
                        + inv.node().label()
                        + "' is only supported at the top level of a workflow on the in-process engine.");
    }
}
