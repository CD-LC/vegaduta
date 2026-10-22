package com.agenticai.core.workflow;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure data nodes (set, filter, sort, aggregate, limit, dedupe, switch, merge); logic lives in
 * WorkflowDataOps.
 */
@Component
class DataNodeAdapter implements NodeHandler {

    private final WorkflowDataNodeHandler dataNodeHandler;

    DataNodeAdapter(ObjectMapper objectMapper) {
        this.dataNodeHandler = new WorkflowDataNodeHandler(objectMapper);
    }

    @Override
    public Set<String> nodeTypes() {
        return Set.of("set", "filter", "sort", "aggregate", "limit", "dedupe", "switch", "merge");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        WorkflowNodeDto node = inv.node();
        String input = inv.input();
        return switch (node.type().toLowerCase(java.util.Locale.ROOT)) {
            case "set" -> dataNodeHandler.set(node, input);
            case "filter" -> dataNodeHandler.filter(node, input);
            case "sort" -> dataNodeHandler.sort(node, input);
            case "aggregate" -> dataNodeHandler.aggregate(node, input);
            case "limit" -> dataNodeHandler.limit(node, input);
            case "dedupe" -> dataNodeHandler.dedupe(node, input);
            case "switch" -> dataNodeHandler.switchNode(node, input);
            case "merge" -> dataNodeHandler.merge(node, input);
            default -> throw new IllegalStateException("Not a data node: " + node.type());
        };
    }
}
