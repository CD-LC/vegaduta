package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.truncate;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import java.util.Set;
import org.springframework.stereotype.Component;

/** "trigger": passes the run input straight through. */
@Component
class TriggerNodeHandler implements NodeHandler {

    @Override
    public Set<String> nodeTypes() {
        return Set.of("trigger");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return NodeExecutionResult.ok(inv.input(), "Received: " + truncate(inv.input(), 200), null);
    }
}
