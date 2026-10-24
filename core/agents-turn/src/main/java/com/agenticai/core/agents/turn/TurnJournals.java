package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.turnjournal.TurnJournalGateway;
import com.agenticai.core.agents.turnjournal.TurnRecorder;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.ai.tool.ToolCallback;

/**
 * How a turn depth starts and wraps its turn journal record (ADR-0020), so the streamed depth, the buffered depths
 * and the delegated turns record the same thing the same way: one INTAKE, the tools wrapped, and - on the turn that
 * continues a task after a human decided a gated call - the decision as an APPROVAL event that names the approval
 * request, which is the same id the {@code pending} APPROVAL event of the turn that was parked carries.
 */
final class TurnJournals {

    private TurnJournals() {}

    /** A buffered depth's record: INTAKE and, on an approval continuation, the decision; NOOP while the journal is off. */
    static TurnRecorder begin(
            TurnContext context,
            UUID agentId,
            String sessionId,
            String userMessage,
            List<ChatAttachmentPayload> attachments,
            List<ToolCallback> tools) {
        return begin(
                context,
                agentId,
                sessionId,
                userMessage,
                attachments == null ? 0 : attachments.size(),
                tools,
                TurnRecorder.currentTurnId());
    }

    /**
     * Starts the record of a buffered or delegated turn. {@code parentTurnId} is the journaled turn whose tool call
     * started this one, or null.
     */
    static TurnRecorder begin(
            TurnContext context,
            UUID agentId,
            String sessionId,
            String userMessage,
            int attachments,
            List<ToolCallback> tools,
            UUID parentTurnId) {
        TurnRecorder recorder =
                TurnJournalGateway.begin(agentId, context == null ? null : context.turnId(), sessionId);
        recorder.intake(
                userMessage,
                attachments,
                tools == null ? 0 : tools.size(),
                parentTurnId,
                runToolNames(context));
        recordApprovalDecision(recorder, context);
        return recorder;
    }

    /** The run-scoped tool selection the turn was narrowed to, or null when it was not (see {@code ChatTurnRequest#toolNames}). */
    static List<String> runToolNames(TurnContext context) {
        return context == null || context.approval() == null ? null : context.approval().runToolNames();
    }

    /** The human's decision on the gated call this turn continues from, or nothing on an ordinary turn. */
    static void recordApprovalDecision(TurnRecorder recorder, TurnContext context) {
        ApprovalContinuation continuation = context == null ? null : context.continuation();
        if (continuation == null || !recorder.active()) {
            return;
        }
        String outcome =
                continuation.outcome() == null
                        ? "unknown"
                        : continuation.outcome().name().toLowerCase(Locale.ROOT);
        recorder.approval(
                String.valueOf(continuation.toolName()), "decided:" + outcome, continuation.requestId());
    }
}
