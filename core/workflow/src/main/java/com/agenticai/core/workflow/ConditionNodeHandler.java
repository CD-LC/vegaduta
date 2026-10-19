package com.agenticai.core.workflow;

import static com.agenticai.core.workflow.WorkflowNodeSupport.stringConfig;
import static com.agenticai.core.workflow.WorkflowNodeSupport.truncate;

import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.Set;
import org.springframework.stereotype.Component;

/** "condition": routes on a text match or a boolean expression (branch true/false). */
@Component
class ConditionNodeHandler implements NodeHandler {

    @Override
    public Set<String> nodeTypes() {
        return Set.of("condition");
    }

    @Override
    public NodeExecutionResult handle(NodeInvocation inv) {
        return executeConditionNode(inv.node(), inv.input(), inv.evalInfo(), inv.scope(), inv.expressions());
    }

    private NodeExecutionResult executeConditionNode(
            WorkflowNodeDto node,
            String input,
            WorkflowExpressionResolver.NodeEvalInfo evalInfo,
            ExpressionScope scope,
            WorkflowExpressionResolver expressions) {
        // "expression" mode: a boolean expression written without braces, e.g. $json.total > 1000.
        // The
        // original "contains" text match stays the default, so every existing condition behaves as
        // before.
        if ("expression".equalsIgnoreCase(stringConfig(node, "mode", "contains"))) {
            String expression = stringConfig(node, "expression", "");
            boolean matched = expressions.evaluateCondition(expression, input, evalInfo, scope);
            return NodeExecutionResult.ok(
                    input,
                    "Condition expression '"
                            + truncate(expression.strip(), 120)
                            + "' -> "
                            + matched,
                    matched ? "true" : "false");
        }
        Object containsRaw = node.config().get("contains");
        String needle = containsRaw == null ? "" : containsRaw.toString();
        boolean matched =
                !needle.isBlank()
                        && input != null
                        && input.toLowerCase(java.util.Locale.ROOT)
                                .contains(needle.toLowerCase(java.util.Locale.ROOT));
        return NodeExecutionResult.ok(
                input, "Condition '" + needle + "' -> " + matched, matched ? "true" : "false");
    }
}
