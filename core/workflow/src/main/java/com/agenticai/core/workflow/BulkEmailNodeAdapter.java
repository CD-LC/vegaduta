package com.agenticai.core.workflow;


import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import java.util.Set;
import org.springframework.stereotype.Component;

/** "bulk_email": see {@link BulkEmailNodeHandler}. */
@Component
class BulkEmailNodeAdapter implements NodeHandler {

    // Null when built without the bulk-email bean (slim test contexts): the node then reports itself
    // unavailable instead of failing the run.
    private final BulkEmailNodeHandler bulkEmailNodeHandler;
    BulkEmailNodeAdapter(BulkEmailNodeHandler bulkEmailNodeHandler) {
        this.bulkEmailNodeHandler = bulkEmailNodeHandler;
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("bulk_email");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        if (bulkEmailNodeHandler == null) {
            return NodeExecutionResult.skipped(
                    inv.input(), "Bulk Email is not available in this deployment.");
        }
        // Subject and body are rendered per recipient (bare {{name}} = that recipient's field,
        // $node/$trigger still resolve against the run), so the handler gets a renderer instead of
        // pre-resolved text.
        return bulkEmailNodeHandler.execute(
                inv.runId(),
                inv.node(),
                inv.input(),
                inv.expressions().recipientRenderer(inv.input(), inv.evalInfo(), inv.scope()));
    }


}
