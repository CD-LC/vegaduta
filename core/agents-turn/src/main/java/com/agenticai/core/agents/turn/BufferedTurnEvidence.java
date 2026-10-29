package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.tools.ToolCallLogService;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tool-log reads a buffered turn's retry/re-plan checks run, factored out of {@link
 * BufferedTurnCalls} for size: "did a tool run this turn" and "what did this turn's first tool
 * failure say", both scoped to the calls logged since the turn's {@code .call()}.
 */
final class BufferedTurnEvidence {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private BufferedTurnEvidence() {}

    /** True when the tool-call log shows no tool call for this session since {@code since}. */
    static boolean noToolRanSince(ChatTurnExecutor turns, UUID agentId, String sessionId, Instant since) {
        try {
            return turns.collectToolEvidenceSince(agentId, sessionId, since) == null;
        } catch (RuntimeException e) {
            log.warn(
                    "Agent {} session {}: could not tell whether a tool ran this turn; not retrying the tool call",
                    agentId,
                    sessionId,
                    e);
            return false;
        }
    }

    /**
     * The cheapest reliable "did this plan just break" signal available - see {@link
     * TaskPlanningService}'s javadoc for why this deliberately isn't a separate LLM classification
     * call after every tool result. {@code toolCallLogService} already records every tool call
     * (success or failure) for this session via {@code LoggingToolCallback}; filtering to {@code
     * since} (captured right before this turn's {@code .call()}) isolates THIS turn's tool calls
     * from the session's full history.
     */
    static String firstToolFailureSince(
            ToolCallLogService toolCallLogService, UUID agentId, String sessionId, Instant since) {
        return toolCallLogService.findByAgentIdAndSessionId(agentId, sessionId).stream()
                .filter(
                        entry ->
                                entry.getStartedAt().isAfter(since)
                                        && entry.getErrorMessage() != null
                                        && !entry.getErrorMessage().isBlank())
                .findFirst()
                .map(entry -> entry.getToolName() + ": " + entry.getErrorMessage())
                .orElse(null);
    }
}
