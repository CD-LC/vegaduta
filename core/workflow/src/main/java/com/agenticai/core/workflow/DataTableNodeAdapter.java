package com.agenticai.core.workflow;


import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** "data_table": data table node (default OFF), unavailable when the handler bean is absent. */
@Component
class DataTableNodeAdapter implements NodeHandler {

    // Field-injected and optional: null means the node reports itself unavailable.
    @Autowired(required = false)
    private WorkflowDataTableNodeHandler handler;
    void setHandler(WorkflowDataTableNodeHandler handler) {
        this.handler = handler;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("data_table");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return handler == null
                ? NodeExecutionResult.skipped(
                        inv.input(), "Data table node unavailable - input passed through unchanged.")
                : handler.executeResolved(inv.node(), inv.input());
    }


}
