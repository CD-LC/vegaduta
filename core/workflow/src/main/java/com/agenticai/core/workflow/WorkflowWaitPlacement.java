package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.List;
import java.util.Map;

/**
 * Save-time check of WHERE a Wait node may sit. A durable wait parks the whole run, which only the top-level
 * traversal of the in-process engine can do: as a loop / fan-out body, a parallel branch or a node inside a
 * sub-flow body it would pass validation and then throw "only supported at the top level" on the first run, and the
 * Temporal engine does not run Wait at all. Pure and static so it unit-tests without Spring.
 */
final class WorkflowWaitPlacement {

    private WorkflowWaitPlacement() {
    }

    /**
     * @param nodesById  every node of the graph
     * @param adjacency  source node id to the target ids of its outgoing edges
     * @param temporal   the Temporal execution engine is configured (it cannot run a Wait node anywhere)
     * @param errors     receives one message per misplaced Wait node
     */
    static void validate(
        List<WorkflowNodeDto> nodes, Map<String, WorkflowNodeDto> nodesById, Map<String, List<String>> adjacency,
        boolean temporal, List<String> errors
    ) {
        for (WorkflowNodeDto node : nodes) {
            String type = node.type() == null ? "" : node.type().toLowerCase(java.util.Locale.ROOT);
            if ("wait".equals(type) && temporal) {
                errors.add("Wait node '" + node.label() + "' is not supported on the Temporal execution engine "
                    + "(app.workflow.execution-engine=temporal); use the default in-process engine.");
            }
            if ("loop".equals(type) || "fan_out".equals(type) || "parallel".equals(type)) {
                for (String target : adjacency.getOrDefault(node.id(), List.of())) {
                    WorkflowNodeDto body = nodesById.get(target);
                    if (body != null && "wait".equalsIgnoreCase(body.type())) {
                        errors.add("Wait node '" + body.label() + "' cannot be " + describe(type)
                            + " - a Wait parks the whole run, so it has to sit on the main path of the workflow.");
                    }
                }
            }
            if ("sub_flow".equals(type) && bodyHasWait(node.config().get("body"), 0)) {
                errors.add("Sub-Flow node '" + node.label() + "' contains a Wait node in its body - a Wait parks the "
                    + "whole run, so it has to sit on the main path of the workflow, not inside a sub-flow.");
            }
        }
    }

    private static String describe(String containerType) {
        return switch (containerType) {
            case "loop" -> "the body of a Loop";
            case "fan_out" -> "the worker of a Fan-out";
            default -> "a branch of a Parallel node";
        };
    }

    private static boolean bodyHasWait(Object body, int depth) {
        if (depth > 8 || !(body instanceof Map<?, ?> bodyMap) || !(bodyMap.get("nodes") instanceof List<?> nodes)) {
            return false;
        }
        for (Object n : nodes) {
            if (!(n instanceof Map<?, ?> nodeMap)) {
                continue;
            }
            Object type = nodeMap.get("type");
            if (type != null && "wait".equalsIgnoreCase(type.toString())) {
                return true;
            }
            if (type != null && "sub_flow".equalsIgnoreCase(type.toString())
                && nodeMap.get("data") instanceof Map<?, ?> data && data.get("config") instanceof Map<?, ?> cfg
                && bodyHasWait(cfg.get("body"), depth + 1)) {
                return true;
            }
        }
        return false;
    }
}
