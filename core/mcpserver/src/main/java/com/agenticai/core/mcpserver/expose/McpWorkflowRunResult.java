package com.agenticai.core.mcpserver.expose;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.workflow.WorkflowRunStatus;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Pure mapping from a {@link WorkflowRunResponse} to the compact JSON an MCP client gets back from a
 * {@code workflow_*} tool or {@code get_run}: status, the last step's output, an approximate cost
 * (sum of the per-step costs the engine already records) and a trimmed step list. Node inputs, tool
 * call payloads and log lines are deliberately NOT included - they can carry far more than the
 * caller asked for.
 */
public final class McpWorkflowRunResult {

    /** Statuses in which a human has to act inside the platform before the run can go on - not answerable over MCP. */
    static final Set<WorkflowRunStatus> NEEDS_HUMAN = Set.of(
        WorkflowRunStatus.AWAITING_APPROVAL, WorkflowRunStatus.AWAITING_INPUT, WorkflowRunStatus.AWAITING_VOICE_INPUT);

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final int MAX_STEPS = 50;

    private McpWorkflowRunResult() {
        // utility class
    }

    public static boolean needsHuman(WorkflowRunStatus status) {
        return status != null && NEEDS_HUMAN.contains(status);
    }

    /** Sum of the recorded per-step costs, or null when no step recorded one. */
    public static BigDecimal totalCostUsd(WorkflowRunResponse run) {
        BigDecimal total = null;
        List<NodeTraceEntry> trace = run.trace();
        if (trace == null) {
            return null;
        }
        for (NodeTraceEntry entry : trace) {
            if (entry != null && entry.costUsd() != null) {
                total = total == null ? entry.costUsd() : total.add(entry.costUsd());
            }
        }
        return total;
    }

    /** Last step output, truncated to {@code maxOutputChars}; null when no step produced one. */
    static String lastOutput(WorkflowRunResponse run, int maxOutputChars) {
        List<NodeTraceEntry> trace = run.trace();
        if (trace == null) {
            return null;
        }
        for (int i = trace.size() - 1; i >= 0; i--) {
            NodeTraceEntry entry = trace.get(i);
            if (entry != null && entry.outputJson() != null && !entry.outputJson().isBlank()) {
                return truncate(entry.outputJson(), maxOutputChars);
            }
        }
        return null;
    }

    public static String truncate(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "... [truncated]";
    }

    /** JSON text for the tool result. {@code hint} (nullable) tells the caller what to do next, e.g. poll with get_run. */
    public static String toJson(WorkflowRunResponse run, int maxOutputChars, String hint) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("run_id", run.id() == null ? null : run.id().toString());
        root.put("workflow_id", run.workflowId() == null ? null : run.workflowId().toString());
        root.put("status", run.status() == null ? null : run.status().name());
        if (run.errorMessage() != null && !run.errorMessage().isBlank()) {
            root.put("error_message", truncate(run.errorMessage(), 2000));
        }
        String output = lastOutput(run, maxOutputChars);
        if (output != null) {
            root.put("output", output);
        }
        BigDecimal cost = totalCostUsd(run);
        if (cost != null) {
            root.put("approx_cost_usd", cost.toPlainString());
        }
        if (run.trace() != null && !run.trace().isEmpty()) {
            ArrayNode steps = root.putArray("steps");
            int count = 0;
            for (NodeTraceEntry entry : run.trace()) {
                if (entry == null || count++ >= MAX_STEPS) {
                    continue;
                }
                ObjectNode step = steps.addObject();
                step.put("node", entry.label() != null && !entry.label().isBlank() ? entry.label() : entry.nodeId());
                step.put("status", entry.status());
                step.put("summary", truncate(entry.summary(), 300));
            }
        }
        if (hint != null && !hint.isBlank()) {
            root.put("hint", hint);
        }
        return root.toString();
    }
}
