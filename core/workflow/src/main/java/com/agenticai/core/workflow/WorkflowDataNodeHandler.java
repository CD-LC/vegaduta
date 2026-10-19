package com.agenticai.core.workflow;

import com.agenticai.core.workflow.WorkflowDataOps.Outcome;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Executor-side adapter for the eight pure data nodes ({@code set}, {@code filter}, {@code sort},
 * {@code aggregate}, {@code limit}, {@code dedupe}, {@code switch}, {@code merge}). All semantics live in
 * {@link WorkflowDataOps}; this class only maps an {@link Outcome} (or a configuration / input problem) onto the
 * {@link NodeExecutionResult} the engine understands. Like {@code WorkflowHttpMediaNodeHandler} it is a plain
 * object the executor constructs, not a Spring bean.
 *
 * <p>The node's config arrives with every expression field already resolved by {@link WorkflowExpressionResolver}
 * (see {@link ExpressionFields}), so nothing here evaluates {@code {{ }}}.
 *
 * <p>Failure model: a node that cannot do its job on this input (not a JSON array, an unknown operator, a bad
 * regex, a value that is not a number) is SKIPPED with the reason, input passed through - the Transform node's
 * posture. A {@code switch} that fails is SKIPPED too but still routes to its {@code default} port: with no branch
 * the engine would follow the first outgoing edge, which for a router is an arbitrary case.
 */
final class WorkflowDataNodeHandler {

    private final WorkflowDataOps ops;

    WorkflowDataNodeHandler(ObjectMapper objectMapper) {
        this.ops = new WorkflowDataOps(objectMapper);
    }

    NodeExecutionResult set(WorkflowNodeDto node, String input) {
        return run("Set", input, false, () -> ops.set(node.config(), input));
    }

    NodeExecutionResult filter(WorkflowNodeDto node, String input) {
        return run("Filter", input, false, () -> ops.filter(node.config(), input));
    }

    NodeExecutionResult sort(WorkflowNodeDto node, String input) {
        return run("Sort", input, false, () -> ops.sort(node.config(), input));
    }

    NodeExecutionResult aggregate(WorkflowNodeDto node, String input) {
        return run("Aggregate", input, false, () -> ops.aggregate(node.config(), input));
    }

    NodeExecutionResult limit(WorkflowNodeDto node, String input) {
        return run("Limit", input, false, () -> ops.limit(node.config(), input));
    }

    NodeExecutionResult dedupe(WorkflowNodeDto node, String input) {
        return run("Remove Duplicates", input, false, () -> ops.dedupe(node.config(), input));
    }

    NodeExecutionResult switchNode(WorkflowNodeDto node, String input) {
        return run("Switch", input, true, () -> ops.switchRoute(node.config(), input));
    }

    NodeExecutionResult merge(WorkflowNodeDto node, String input) {
        return run("Merge", input, false, () -> ops.merge(node.config(), input));
    }

    private NodeExecutionResult run(String name, String input, boolean routesToDefaultOnFailure, Supplier<Outcome> op) {
        try {
            Outcome outcome = op.get();
            if (outcome.skipped()) {
                return NodeExecutionResult.skipped(input, outcome.summary(), outcome.log());
            }
            return NodeExecutionResult.ok(outcome.output(), outcome.summary(), outcome.branch(), outcome.log());
        } catch (IllegalArgumentException | IllegalStateException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            List<String> log = new ArrayList<>();
            log.add(name + " failed: " + reason);
            if (routesToDefaultOnFailure) {
                // Same wording shape as the supervisor's fail-open so the run overlay can read the chosen handle.
                return new NodeExecutionResult(input, "SKIPPED",
                    name + " defaulted to '" + WorkflowDataOps.SWITCH_DEFAULT_HANDLE + "' (" + reason + ")",
                    WorkflowDataOps.SWITCH_DEFAULT_HANDLE, null, log, null, null);
            }
            return NodeExecutionResult.skipped(input, name + " failed: " + reason + " - input passed through unchanged.", log);
        }
    }
}
