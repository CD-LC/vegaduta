package com.agenticai.core.workflow;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import java.util.Set;

/**
 * One node type's execution logic. {@link WorkflowNodeExecutor} owns the cross-cutting steps (pin
 * lookup, approval gate, expression resolution, dry-run, run-data recording) and then looks the
 * node's lower-cased {@code type} up in the registry built from every {@code NodeHandler} bean.
 * Handlers receive already-resolved config and must not substitute {{ }} themselves.
 *
 * <p>A handler is stateless with respect to a run: everything run-specific arrives in the {@link
 * NodeInvocation}. Two handlers claiming the same type fail executor construction.
 */
public interface NodeHandler {

    /** Lower-case node types this handler executes (the {@code type} of a {@code WorkflowNodeDto}). */
    Set<String> nodeTypes();

    NodeExecutionResult handle(NodeInvocation invocation);
}
