package com.agenticai.core.quality.dataset;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The seam through which the eval-dataset runner replays a case as a real
 * agent turn.
 *
 * <p>Why a seam exists at all: {@code core/quality}'s pom states its own
 * boundary - "No dependencies on agents (to avoid circular dependency)" -
 * so this module cannot name {@code AgentChatService} at compile time, and
 * this workstream's file ownership does not include {@code core/agents} (or
 * either pom), so neither a {@code agents -> quality} dependency edge nor an
 * agents-side implementation could be added here. The interface is the
 * stable contract; {@link ReflectiveAgentChatTurnReplayer} is the interim
 * in-module bridge that makes the feature actually work today, and a
 * first-class {@code core/agents}-side implementation (which would win via
 * {@code @ConditionalOnMissingBean} semantics - see the bridge's javadoc)
 * is recorded as the cross-workstream follow-up.
 *
 * <p>Implementations MUST be called with {@code TenantContext} already set
 * on the calling thread (the run worker sets it from the run row, exactly
 * like {@code AgentJobExecution#run}) - the underlying chat pipeline reads
 * it synchronously for the kill-switch check, plan resolution and schema
 * routing.
 */
public interface AgentTurnReplayer {

    /**
     * Replays one user message as a single agent turn and blocks for the
     * complete reply.
     *
     * @param agentId the agent under test
     * @param sessionId a UNIQUE session id per case (the runner uses
     *     {@code "eval:<runId>:<n>"}) so session memory can never bleed one
     *     case's context into the next - each golden case must be judged
     *     cold, or a passing run would depend on case ordering
     * @param subjectKey the HUMAN requester's subject, snapshotted on the
     *     run row - the replayed turns are metered against this key by
     *     {@code PerUserBudgetService}, so a run can never spend money no
     *     human owns ({@code AgentJobService#submit}'s reasoning)
     * @param userMessage the case's user turn
     * @param executeTools full pipeline with tools when {@code true}
     *     (dataset opted in), tool-less ({@code chatTextOnly}) otherwise -
     *     see {@code EvalDataset#isToolExecutionEnabled} for why tool-less
     *     is the default posture
     * @param timeout how long to block for the full reply
     * @return the reply plus the tool calls the turn made (always empty for
     *     tool-less replays)
     * @throws Exception any replay failure - the runner records it as an
     *     ERROR case result and moves on (per-case isolation)
     */
    ReplayedTurn replay(
        UUID agentId, String sessionId, String subjectKey, String userMessage,
        boolean executeTools, Duration timeout
    ) throws Exception;

    /** One tool invocation observed during a replayed turn. */
    record ReplayedToolCall(String toolName, String input, String output) {
    }

    /** The observable outcome of one replayed turn. */
    record ReplayedTurn(String responseText, List<ReplayedToolCall> toolCalls) {
    }
}
