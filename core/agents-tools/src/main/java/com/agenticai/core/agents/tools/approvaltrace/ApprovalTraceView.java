package com.agenticai.core.agents.tools.approvaltrace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What an approval led to: the decision, the conversation it belongs to and the tool calls the
 * agent made in that conversation after the decision. Read-only; assembled from records that
 * already exist.
 *
 * @param conversation null when the request did not come from an agent turn (an admin screen, a
 *     webhook, a scheduler): nothing was waiting for it, so nothing continued.
 * @param calls the agent's tool calls in that conversation from the decision until {@code
 *     windowEnd}, oldest first, capped at {@link ApprovalTraceService#MAX_CALLS}. They are matched
 *     by conversation and time, not individually tied to this approval.
 * @param traceNote says in words what the trace covers and what it cannot show (never null).
 */
public record ApprovalTraceView(
        UUID requestId,
        String actionType,
        String status,
        Instant createdAt,
        String requestedBy,
        String decidedBy,
        Instant decidedAt,
        String rejectionReason,
        String executionResult,
        String executionError,
        int retryCount,
        Conversation conversation,
        Instant windowEnd,
        boolean callsTruncated,
        List<Call> calls,
        String traceNote) {

    /** The conversation the approved action belongs to, from the request's replay envelope. */
    public record Conversation(UUID agentId, String sessionId, String goal) {}

    /** One tool call: sanitized, truncated input and output. */
    public record Call(
            UUID id,
            String toolName,
            Instant startedAt,
            Instant completedAt,
            String outcome,
            String inputSummary,
            String outputSummary,
            String errorMessage) {}
}
