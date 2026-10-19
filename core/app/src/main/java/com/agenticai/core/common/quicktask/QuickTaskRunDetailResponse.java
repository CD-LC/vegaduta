package com.agenticai.core.common.quicktask;

import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.tools.ToolCallLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The full, untruncated record for one Quick Task run - "the complete log with request sent and
 * response back - not just half of it - any point of time." {@code requestMessage}/{@code
 * resultText}/{@code errorMessage} and every {@code toolCalls[].input}/{@code output} are the real,
 * complete TEXT column values (see {@code ToolCallLog}'s columns) with nothing shortened
 * server-side - any truncation the person previously saw was a frontend CSS display choice
 * (single-line ellipsis in the live trace card), never a gap in what was actually stored or sent
 * over the wire.
 */
public record QuickTaskRunDetailResponse(
        String taskId,
        UUID agentId,
        QuickTaskChannel channel,
        QuickTaskRunStatus status,
        String requestMessage,
        String resultText,
        String deliveryNote,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        List<ToolCallEntry> toolCalls,
        // C1 pin: the agent_version.id this run resolved at start. Null for
        // runs that predate the column or ran with no label resolved - the
        // console renders it as a version badge when present.
        UUID agentVersionId,
        // Engine disclosure for the detail view - which agent powered this run
        // and on what provider/model. Null when the agent was deleted or could
        // not be resolved; the UI treats nulls as "not disclosed", never guesses.
        String agentName,
        String providerName,
        String model) {
    static QuickTaskRunDetailResponse from(
            QuickTaskRun run, List<ToolCallLog> toolCalls, AgentResponse agent) {
        return new QuickTaskRunDetailResponse(
                run.getTaskId(),
                run.getAgentId(),
                run.getChannel(),
                run.getStatus(),
                run.getRequestMessage(),
                run.getResultText(),
                run.getDeliveryNote(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getCompletedAt(),
                toolCalls.stream().map(ToolCallEntry::from).toList(),
                run.getAgentVersionId(),
                agent == null ? null : agent.name(),
                agent == null || agent.providerName() == null ? null : agent.providerName().name(),
                agent == null ? null : agent.model());
    }

    public record ToolCallEntry(
            String toolName,
            String toolInput,
            String toolOutput,
            String errorMessage,
            Instant startedAt,
            Instant completedAt) {
        static ToolCallEntry from(ToolCallLog log) {
            return new ToolCallEntry(
                    log.getToolName(),
                    log.getToolInput(),
                    log.getToolOutput(),
                    log.getErrorMessage(),
                    log.getStartedAt(),
                    log.getCompletedAt());
        }
    }
}
