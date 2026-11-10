package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;

/**
 * What follows a persisted streamed reply: the {@code agent.message.completed} event and the
 * sampled quality judge. Runs on the stream-completion thread, which has no {@link TenantContext}:
 * the event carries its tenant as data (the publisher applies it), and {@link
 * LlmQualityJudgeService#sampleAsync} snapshots the CALLING thread's context for the judge task and
 * its {@code llm_call_log} row, so the context captured on the request thread is re-applied around
 * that call. Without it the judge ran in the default schema and every row failed with {@code
 * relation "llm_call_log" does not exist}.
 */
final class StreamedReplyCompletion {

    private StreamedReplyCompletion() {}

    /** The reply's identity, passed through unchanged to the event and the judge. */
    record Reply(
            ChatClient chatClient,
            UUID agentId,
            String sessionId,
            String tenantId,
            String userMessage,
            String persistedAnswer,
            String fullAnswer,
            boolean pausedForApproval) {}

    /**
     * True when the turn ended on a call held for a human decision: its reply is the platform's
     * "waiting for approval" note, not an answer worth a quality check, and a judge call started then
     * would run while the continuation after the decision is being answered.
     */
    static boolean pausedForApproval(ToolLedger ledger) {
        return ledger != null
                && ledger.entries().stream().anyMatch(e -> e.status() == ToolLedger.Status.AWAITING_APPROVAL);
    }

    static void afterPersisted(
            AgentMessageEvents events,
            LlmQualityJudgeService judge,
            Reply reply,
            Optional<TenantContext> capturedTenant) {
        events.emitAgentMessageCompleted(
                reply.agentId(),
                reply.sessionId(),
                reply.tenantId(),
                reply.persistedAnswer(),
                "live");
        if (reply.pausedForApproval()) {
            return;
        }
        // Gap 2 - fire-and-forget, never affects this already-completed stream.
        TenantScope.of(capturedTenant)
                .run(
                        () ->
                                judge.sampleAsync(
                                        reply.chatClient(),
                                        reply.agentId(),
                                        reply.sessionId(),
                                        reply.userMessage(),
                                        reply.fullAnswer()));
    }
}
