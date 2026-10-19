package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentReliabilitySummary;
import com.agenticai.core.agents.tools.AgentToolCallStats;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.chat.AgentLlmCallStats;
import com.agenticai.core.chat.LlmCallLog;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.sharing.PermissionLevel;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task #959 ("Agent performance: response time + reliability + failure handling") - computes {@link
 * AgentReliabilitySummary} for one agent by combining three data sources this platform already
 * durably records, rather than standing up a parallel per-agent metrics pipeline:
 *
 * <ul>
 *   <li>{@link LlmCallLogService} (backed by {@code llm_call_log}, written by {@code
 *       LlmMetricsAdvisor} on every real LLM call) - average response time, success/failure count,
 *       and the recent-failure list. Every row already carries {@code agentId} for agent chat turns
 *       and Quick Task runs (see {@code AgentChatService}'s {@code
 *       LlmMetricsAdvisor.AGENT_ID_PARAM} wiring), so this is a straight {@code WHERE agent_id =
 *       ...} read, not new instrumentation.
 *   <li>{@link ToolCallLogService} (backed by {@code tool_call_log}, written by {@code
 *       LoggingToolCallback} on every tool invocation) - this agent's tool-call volume/failure
 *       count, a distinct signal from LLM call success (a tool can fail mid-turn while the model
 *       still recovers and answers correctly).
 *   <li>{@link AgentService} (the {@code Agent} entity itself) - the already-existing {@code
 *       fallbackTriggeredCount}/{@code fallbackFailedCount}/{@code lastFallbackAt} columns from the
 *       #954-956 fallback-observability follow-up, reused verbatim rather than recomputed.
 * </ul>
 *
 * <p>Deliberately does NOT attempt to derive a per-agent WorkflowRun success/failure rate: {@code
 * WorkflowRun} has no {@code agentId} column (a single run can touch zero, one, or several Agent
 * nodes via their node config), and parsing node configs to attribute a run to "an" agent would be
 * both fragile and a misrepresentation - a run failing on a Code node has nothing to do with any
 * agent's reliability. Every Agent-node execution inside a workflow run already goes through {@code
 * AgentChatService}/{@code LoggingToolCallback} the same as a direct chat turn, so its outcome is
 * still captured here via the LLM-call and tool-call aggregates above - just not double-counted as
 * a separate "workflow success rate" that would need its own, less-precise attribution logic.
 */
@Service
public class AgentReliabilityService {

    private final AgentService agentService;
    private final LlmCallLogService llmCallLogService;
    private final ToolCallLogService toolCallLogService;

    public AgentReliabilityService(
            AgentService agentService,
            LlmCallLogService llmCallLogService,
            ToolCallLogService toolCallLogService) {
        this.agentService = agentService;
        this.llmCallLogService = llmCallLogService;
        this.toolCallLogService = toolCallLogService;
    }

    @Transactional(readOnly = true)
    public AgentReliabilitySummary summarize(UUID agentId) {
        // Confirms the agent exists (and is in this tenant's schema) before
        // spending three more queries on it - same 404-on-unknown-id shape
        // every other per-agent endpoint on AgentController already has.
        // Cross-user resource sharing retrofit (Round 2) - VIEWER-gated, same
        // "internal detail beyond RUNNER" bar as memories/tool-calls.
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.VIEWER);

        AgentLlmCallStats llmStats = llmCallLogService.statsForAgent(agentId);
        AgentToolCallStats toolStats = toolCallLogService.statsForAgent(agentId);
        List<LlmCallLog> recentFailures = llmCallLogService.recentFailuresForAgent(agentId);

        Double successRatePercent =
                llmStats.totalCalls() == 0
                        ? null
                        : roundToTwoDecimals(
                                ((llmStats.totalCalls() - llmStats.failedCalls()) * 100.0)
                                        / llmStats.totalCalls());
        Double toolSuccessRatePercent =
                toolStats.totalCalls() == 0
                        ? null
                        : roundToTwoDecimals(
                                ((toolStats.totalCalls() - toolStats.failedCalls()) * 100.0)
                                        / toolStats.totalCalls());

        return new AgentReliabilitySummary(
                agentId,
                llmStats.totalCalls(),
                llmStats.failedCalls(),
                successRatePercent,
                llmStats.avgLatencyMs() == null ? null : Math.round(llmStats.avgLatencyMs()),
                llmStats.firstCallAt(),
                llmStats.lastCallAt(),
                toolStats.totalCalls(),
                toolStats.failedCalls(),
                toolSuccessRatePercent,
                agent.getFallbackTriggeredCount(),
                agent.getFallbackFailedCount(),
                agent.getLastFallbackAt(),
                recentFailures.stream()
                        .map(
                                row ->
                                        new AgentReliabilitySummary.RecentFailure(
                                                row.getStartedAt(),
                                                row.getErrorMessage(),
                                                row.getExceptionType(),
                                                row.getProviderName() == null
                                                        ? null
                                                        : row.getProviderName().name(),
                                                row.getModel()))
                        .toList());
    }

    private static Double roundToTwoDecimals(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
