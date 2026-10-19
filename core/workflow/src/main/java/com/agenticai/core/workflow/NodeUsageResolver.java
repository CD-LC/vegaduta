package com.agenticai.core.workflow;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.chat.LlmCallLog;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.workflow.dto.NodeTraceEntry;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Fills {@link NodeTraceEntry#tokensIn()}/{@code tokensOut}/{@code costUsd} for Agent and
 * Supervisor nodes from rows {@code LlmMetricsAdvisor} already writes to {@code llm_call_log} for
 * every model call - no new accounting.
 *
 * <p>Attribution: both node types call {@code AgentChatService.chat} with the workflow run id as
 * the session id (see {@code WorkflowNodeExecutor}), so a node's calls are the rows with {@code
 * session_id = runId}, {@code agent_id =} the node's configured agent, and {@code started_at}
 * inside the node's own start/end. That is only unambiguous when no other node with the same agent
 * runs concurrently in the same run, so the engine does NOT call this for parallel/fan-out entries
 * - their usage stays null.
 *
 * <p>Null means unknown, never zero: no rows (step-cache hit, the economy model path, which
 * bypasses the advisor), all token columns null, a lookup failure, or a FREE tenant. The last one
 * is a product rule, not a data gap: per-call tokens/cost are a Premium feature ({@code
 * AgentSessionLlmUsageController#assertPremiumPlan}) and the trace must not be a back door around
 * it.
 *
 * <p><b>Tool-call child rows</b> (phase 3) come from {@code tool_call_log} with the same
 * attribution rule (agent + session = run id + the node's time window) and are NOT plan-gated -
 * they are what the agent did, not what it cost, and the agent's own tool-call history is already
 * visible to its users. Inputs/outputs go through {@link NodeTracePayloads} (redacted, {@link
 * #MAX_TOOL_PAYLOAD_CHARS} each); at most {@link #MAX_TOOL_CALLS} rows.
 */
@Component
public class NodeUsageResolver {

    private static final Logger log = LoggerFactory.getLogger(NodeUsageResolver.class);

    private final ObjectProvider<LlmCallLogService> callLogService;
    private final ObjectProvider<TenantLimits> tenantLimits;
    private final ObjectProvider<ToolCallLogRepository> toolCallLogs;

    static final int MAX_TOOL_CALLS = 25;
    static final int MAX_TOOL_PAYLOAD_CHARS = 2 * 1024;

    public NodeUsageResolver(
            ObjectProvider<LlmCallLogService> callLogService,
            ObjectProvider<TenantLimits> tenantLimits,
            ObjectProvider<ToolCallLogRepository> toolCallLogs) {
        this.callLogService = callLogService;
        this.tenantLimits = tenantLimits;
        this.toolCallLogs = toolCallLogs;
    }

    /** Aggregated usage; any component may be null (unknown). */
    public record Usage(Long tokensIn, Long tokensOut, BigDecimal costUsd) {}

    /**
     * {@code entry} with usage attached when it can be attributed; otherwise {@code entry}
     * unchanged.
     */
    public NodeTraceEntry attach(NodeTraceEntry entry, WorkflowNodeDto node, String sessionId) {
        Usage usage = resolve(node, sessionId, entry.startedAt(), entry.completedAt());
        NodeTraceEntry out =
                usage == null
                        ? entry
                        : entry.withUsage(usage.tokensIn(), usage.tokensOut(), usage.costUsd());
        List<NodeTraceEntry.ToolCallTrace> calls =
                toolCalls(node, sessionId, entry.startedAt(), entry.completedAt());
        return calls == null ? out : out.withToolCalls(calls);
    }

    List<NodeTraceEntry.ToolCallTrace> toolCalls(
            WorkflowNodeDto node, String sessionId, Instant from, Instant to) {
        if (node == null
                || sessionId == null
                || from == null
                || to == null
                || !"agent".equalsIgnoreCase(node.type())) {
            return null;
        }
        ToolCallLogRepository repo = toolCallLogs.getIfAvailable();
        Object agentIdRaw = node.config().get("agentId");
        if (repo == null || agentIdRaw == null || agentIdRaw.toString().isBlank()) {
            return null;
        }
        try {
            UUID agentId = UUID.fromString(agentIdRaw.toString());
            List<NodeTraceEntry.ToolCallTrace> rows = new ArrayList<>();
            for (ToolCallLog call :
                    repo.findByAgentIdAndSessionIdOrderByStartedAtAsc(agentId, sessionId)) {
                Instant started = call.getStartedAt();
                if (started == null || started.isBefore(from) || started.isAfter(to)) {
                    continue;
                }
                if (rows.size() >= MAX_TOOL_CALLS) {
                    break;
                }
                Long ms =
                        call.getCompletedAt() == null
                                ? null
                                : Math.max(
                                        0L,
                                        Duration.between(started, call.getCompletedAt())
                                                .toMillis());
                rows.add(
                        new NodeTraceEntry.ToolCallTrace(
                                call.getToolName(),
                                NodeTracePayloads.sanitize(
                                                call.getToolInput(), MAX_TOOL_PAYLOAD_CHARS)
                                        .text(),
                                NodeTracePayloads.sanitize(
                                                call.getToolOutput(), MAX_TOOL_PAYLOAD_CHARS)
                                        .text(),
                                NodeTracePayloads.sanitize(
                                                call.getErrorMessage(), MAX_TOOL_PAYLOAD_CHARS)
                                        .text(),
                                started,
                                ms));
            }
            return rows.isEmpty() ? null : rows;
        } catch (RuntimeException e) {
            log.debug(
                    "Could not attach tool calls to workflow node {} (session {}): {}",
                    node.id(),
                    sessionId,
                    e.getMessage());
            return null;
        }
    }

    Usage resolve(WorkflowNodeDto node, String sessionId, Instant from, Instant to) {
        if (node == null
                || sessionId == null
                || from == null
                || to == null
                || !isLlmNode(node.type())) {
            return null;
        }
        Object agentIdRaw = node.config().get("agentId");
        if (agentIdRaw == null || agentIdRaw.toString().isBlank()) {
            return null;
        }
        LlmCallLogService service = callLogService.getIfAvailable();
        if (service == null || !planAllowsUsage()) {
            return null;
        }
        try {
            UUID agentId = UUID.fromString(agentIdRaw.toString());
            return sum(service.forSessionAgentWindow(sessionId, agentId, from, to));
        } catch (RuntimeException e) {
            log.debug(
                    "Could not attribute LLM usage to workflow node {} (session {}): {}",
                    node.id(),
                    sessionId,
                    e.getMessage());
            return null;
        }
    }

    static boolean isLlmNode(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return "agent".equals(t) || "supervisor".equals(t);
    }

    static Usage sum(List<LlmCallLog> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Long in = null;
        Long out = null;
        BigDecimal cost = null;
        for (LlmCallLog row : rows) {
            if (row.getPromptTokens() != null) {
                in = (in == null ? 0L : in) + row.getPromptTokens();
            }
            if (row.getCompletionTokens() != null) {
                out = (out == null ? 0L : out) + row.getCompletionTokens();
            }
            if (row.getEstimatedCostUsd() != null) {
                cost = (cost == null ? BigDecimal.ZERO : cost).add(row.getEstimatedCostUsd());
            }
        }
        return in == null && out == null && cost == null ? null : new Usage(in, out, cost);
    }

    private boolean planAllowsUsage() {
        TenantLimits limits = tenantLimits.getIfAvailable();
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (limits == null || tenantId == null) {
            // Same default as AgentSessionLlmUsageController: no tenant = UNLIMITED.
            return true;
        }
        return limits.plan(tenantId) != TenantPlan.FREE;
    }
}
