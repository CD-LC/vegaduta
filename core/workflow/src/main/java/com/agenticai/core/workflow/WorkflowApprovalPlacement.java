package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a human-decision node may sit: the main path only, never a loop body, fan-out worker,
 * parallel branch or Sub-Flow body. This is the approval twin of {@link WorkflowWaitPlacement}'s "a
 * Wait parks the whole run" rule: when an Approval node, a Human Input node or any node flagged
 * {@code requiresApproval: true} halts a run (AWAITING_APPROVAL / AWAITING_INPUT), the recorded
 * resume envelope carries the node id and its input - NOT the container's iteration cursor or the
 * outputs already collected. Resuming such a halt would re-enter the main graph at that single node
 * and silently drop the remaining items (and inside a Sub-Flow the body node is not in the main
 * graph's node table at all, so resume fails with "no longer exists"). Until the resume payload
 * grows an iteration cursor, validation refuses the placement rather than letting a graph save a
 * halt the engine cannot resume faithfully.
 */
final class WorkflowApprovalPlacement {

    private WorkflowApprovalPlacement() {}

    /**
     * @param nodesById every node of the graph
     * @param adjacency source node id to the target ids of its outgoing edges
     * @param errors receives one message per misplaced human-decision node
     */
    static void validate(
            List<WorkflowNodeDto> nodes,
            Map<String, WorkflowNodeDto> nodesById,
            Map<String, List<String>> adjacency,
            List<String> errors) {
        for (WorkflowNodeDto node : nodes) {
            String type = node.type() == null ? "" : node.type().toLowerCase(Locale.ROOT);
            if ("loop".equals(type) || "fan_out".equals(type) || "parallel".equals(type)) {
                for (String target : adjacency.getOrDefault(node.id(), List.of())) {
                    WorkflowNodeDto body = nodesById.get(target);
                    if (body == null) {
                        continue;
                    }
                    String kind = gateKind(body.type(), body.config().get("requiresApproval"));
                    if (kind != null) {
                        errors.add(
                                kind
                                        + " node '"
                                        + body.label()
                                        + "' cannot be "
                                        + describe(type)
                                        + " - a human-decision halt parks the run and resume re-enters at that one"
                                        + " node, so the remaining items would never run. Put the gate on the"
                                        + " workflow's main path.");
                    }
                }
            }
            if ("sub_flow".equals(type)) {
                List<String> gated = new ArrayList<>();
                collectGatedBodyNodes(node.config().get("body"), 0, gated);
                for (String label : gated) {
                    errors.add(
                            "Sub-Flow node '"
                                    + node.label()
                                    + "' contains "
                                    + label
                                    + " in its body - a human-decision halt inside a sub-flow cannot resume"
                                    + " (resume only knows the main graph's nodes). Put the gate on the main"
                                    + " path.");
                }
            }
        }
    }

    /** The halt kind a node produces, or null when it never parks a run for a human. */
    private static String gateKind(String type, Object requiresApproval) {
        if ("approval".equalsIgnoreCase(type)) {
            return "an Approval";
        }
        if ("human_input".equalsIgnoreCase(type)) {
            return "a Human Input";
        }
        return Boolean.TRUE.equals(requiresApproval) ? "an approval-gated" : null;
    }

    /**
     * Same walk {@link WorkflowWaitPlacement#bodyHasWait} makes over a Sub-Flow's embedded {@code
     * body.nodes} (raw maps, recursing into nested sub_flow bodies): collect every human-decision
     * node's label/id, most-derived message first.
     */
    private static void collectGatedBodyNodes(Object body, int depth, List<String> gated) {
        if (depth > 8
                || !(body instanceof Map<?, ?> bodyMap)
                || !(bodyMap.get("nodes") instanceof List<?> bodyNodes)) {
            return;
        }
        for (Object n : bodyNodes) {
            if (!(n instanceof Map<?, ?> nodeMap)) {
                continue;
            }
            Object type = nodeMap.get("type");
            Object data = nodeMap.get("data");
            Object config = data instanceof Map<?, ?> d ? d.get("config") : null;
            Object requiresApproval =
                    config instanceof Map<?, ?> c ? c.get("requiresApproval") : null;
            String kind = gateKind(type == null ? "" : type.toString(), requiresApproval);
            if (kind != null) {
                Object label = nodeMap.get("id");
                Object name = data instanceof Map<?, ?> d ? d.get("label") : null;
                gated.add(kind + " node '" + (name != null ? name : label) + "'");
            }
            if (type != null
                    && "sub_flow".equalsIgnoreCase(type.toString())
                    && config instanceof Map<?, ?> c) {
                collectGatedBodyNodes(c.get("body"), depth + 1, gated);
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
}
