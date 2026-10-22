package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * The pure traversal rules shared by BOTH workflow engines: the in-process {@link
 * WorkflowExecutionEngine} and the Temporal {@code WorkflowRunWorkflowImpl}. Functional core: every
 * method is a deterministic function of its arguments - no I/O, no clock, no randomness, no
 * logging, no static mutable state - so it is safe inside Temporal workflow code (replay must reach
 * the same decision from the same history). Changing a rule here changes BOTH engines; for a rule
 * change that could alter an in-flight Temporal history, gate the Temporal call site behind {@code
 * Workflow.getVersion}.
 *
 * <p>The {@link ObjectMapper} is passed in (rather than held) because the in-process engine uses
 * the Spring mapper and the workflow uses {@code JsonMappers.plain()}; parsing is deterministic
 * either way.
 */
public final class WorkflowTraversalRules {

    /**
     * Loop / Iterator bounds: what an author gets with no config, and the ceiling no config can
     * exceed.
     */
    public static final int DEFAULT_MAX_LOOP_ITERATIONS = 25;

    public static final int ABSOLUTE_MAX_LOOP_ITERATIONS = 100;

    /**
     * Dynamic Fan-Out bounds (item count and worker concurrency); see the engine's historic
     * javadoc.
     */
    public static final int DEFAULT_MAX_FAN_OUT_ITEMS = 25;

    public static final int ABSOLUTE_MAX_FAN_OUT_ITEMS = 100;
    public static final int DEFAULT_FAN_OUT_CONCURRENCY = 5;
    public static final int ABSOLUTE_MAX_FAN_OUT_CONCURRENCY = 10;

    private WorkflowTraversalRules() {}

    /**
     * True when {@code edge} leaves its node through the dedicated {@code error} handle (a failure
     * route).
     */
    public static boolean isErrorEdge(WorkflowEdgeDto edge) {
        return "error".equalsIgnoreCase(edge.sourceHandle() == null ? "" : edge.sourceHandle());
    }

    /**
     * Next node for the in-process engine. With a {@code branch} (condition / switch result) the
     * edge whose handle matches it case-insensitively; with none, the first edge that is NOT an
     * {@code error}-handle edge - a node that succeeded must never be routed down its own error
     * branch.
     */
    public static String nextNodeId(List<WorkflowEdgeDto> edgesFromNode, String branch) {
        if (edgesFromNode.isEmpty()) {
            return null;
        }
        if (branch != null) {
            return branchTarget(edgesFromNode, branch);
        }
        return edgesFromNode.stream()
                .filter(e -> !isErrorEdge(e))
                .map(WorkflowEdgeDto::target)
                .findFirst()
                .orElse(null);
    }

    /**
     * Next node for the Temporal workflow: identical to {@link #nextNodeId} for a branch, but with
     * no branch it takes the FIRST edge unconditionally. The Temporal path has no error-edge
     * routing (Tier-3 error edges are an in-process feature), and changing this would alter
     * decisions for histories already recorded, so the divergence is preserved deliberately and
     * named here rather than hidden in two copies. Unifying the two is a behaviour change that
     * needs a {@code Workflow.getVersion} gate.
     */
    public static String nextNodeIdFirstEdge(List<WorkflowEdgeDto> edgesFromNode, String branch) {
        if (edgesFromNode.isEmpty()) {
            return null;
        }
        if (branch != null) {
            return branchTarget(edgesFromNode, branch);
        }
        return edgesFromNode.get(0).target();
    }

    private static String branchTarget(List<WorkflowEdgeDto> edgesFromNode, String branch) {
        return edgesFromNode.stream()
                .filter(e -> branch.equalsIgnoreCase(e.sourceHandle()))
                .map(WorkflowEdgeDto::target)
                .findFirst()
                .orElse(null);
    }

    /** Loop iteration ceiling: clamp, never reject, a non-numeric or out-of-range author value. */
    public static int loopMaxIterations(WorkflowNodeDto node) {
        return clamp(
                node, "maxIterations", DEFAULT_MAX_LOOP_ITERATIONS, ABSOLUTE_MAX_LOOP_ITERATIONS);
    }

    /** Fan-out item ceiling; same clamp-don't-reject shape. */
    public static int fanOutMaxItems(WorkflowNodeDto node) {
        return clamp(node, "maxItems", DEFAULT_MAX_FAN_OUT_ITEMS, ABSOLUTE_MAX_FAN_OUT_ITEMS);
    }

    /** Fan-out worker concurrency; same clamp shape. */
    public static int fanOutConcurrency(WorkflowNodeDto node) {
        return clamp(
                node,
                "maxConcurrency",
                DEFAULT_FAN_OUT_CONCURRENCY,
                ABSOLUTE_MAX_FAN_OUT_CONCURRENCY);
    }

    private static int clamp(WorkflowNodeDto node, String key, int defaultValue, int absoluteMax) {
        Object raw = node.config().get(key);
        if (raw instanceof Number n) {
            return Math.max(1, Math.min(n.intValue(), absoluteMax));
        }
        return defaultValue;
    }

    /**
     * Why {@link #resolveItems} produced no items; lets the caller log without the rule doing I/O.
     */
    public enum ItemsProblem {
        NONE,
        /** The input parsed but is not a JSON array (at {@code itemsField} when set). */
        NOT_A_LIST,
        /** The input is not valid JSON. */
        INVALID_JSON
    }

    /**
     * Items resolved for a loop / fan-out, plus the reason when the list is empty because of bad
     * input.
     */
    public record LoopItems(List<String> items, ItemsProblem problem, String itemsField) {}

    /**
     * Resolves the list a Loop / Fan-out iterates. A blank {@code itemsField} means the input
     * itself must be a JSON array; otherwise the input is a JSON object and that field is read.
     * Anything that does not resolve to an array iterates zero times (reported in {@link
     * LoopItems#problem()}) rather than failing the run. Textual elements are unquoted; every other
     * element is its JSON text.
     */
    public static LoopItems resolveItems(
            ObjectMapper mapper, WorkflowNodeDto node, String currentOutput) {
        Object itemsFieldRaw = node.config().get("itemsField");
        String itemsField = itemsFieldRaw == null ? null : itemsFieldRaw.toString().trim();
        try {
            JsonNode root =
                    mapper.readTree(
                            currentOutput == null || currentOutput.isBlank()
                                    ? "[]"
                                    : currentOutput);
            JsonNode arrayNode =
                    (itemsField == null || itemsField.isEmpty()) ? root : root.path(itemsField);
            if (!arrayNode.isArray()) {
                return new LoopItems(List.of(), ItemsProblem.NOT_A_LIST, itemsField);
            }
            List<String> items = new ArrayList<>();
            for (JsonNode item : arrayNode) {
                items.add(item.isTextual() ? item.asText() : item.toString());
            }
            return new LoopItems(items, ItemsProblem.NONE, itemsField);
        } catch (Exception e) {
            return new LoopItems(List.of(), ItemsProblem.INVALID_JSON, itemsField);
        }
    }

    /** Convenience for callers that do not log: just the items. */
    public static List<String> resolveLoopItems(
            ObjectMapper mapper, WorkflowNodeDto node, String currentOutput) {
        return resolveItems(mapper, node, currentOutput).items();
    }

    /** JSON array text of {@code items}; {@code "[]"} if serialisation fails. */
    public static String writeJsonArray(ObjectMapper mapper, List<String> items) {
        try {
            return mapper.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }
}
