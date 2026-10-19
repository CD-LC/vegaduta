package com.agenticai.core.workflow;


import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** "crm_lead": CRM lead upsert, unavailable when the handler bean is absent. */
@Component
class CrmLeadNodeAdapter implements NodeHandler {

    // Field-injected and optional: null means the node reports itself unavailable.
    @Autowired(required = false)
    private WorkflowCrmLeadNodeHandler handler;
    void setHandler(WorkflowCrmLeadNodeHandler handler) {
        this.handler = handler;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("crm_lead");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return handler == null
                ? NodeExecutionResult.skipped(
                        inv.input(), "CRM lead node unavailable - input passed through unchanged.")
                : handler.executeResolved(inv.node(), inv.input());
    }


}
