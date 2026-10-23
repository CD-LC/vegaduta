package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.approval.ApprovalRequest;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Writes the bracketed "what happened to the approved action" note into the conversation that asked
 * for it. One implementation for every kind of approved action: the gated tool replay
 * ({@link AgentToolCallApprovalExecutor}) and everything the gate reports through {@code
 * ApprovalOutcomePort} ({@link ApprovalOutcomeFollowUp}).
 *
 * <p><b>Which history.</b> Every turn reads and writes history under the SEC-004 scoped id ({@code
 * SessionMemoryService#scopedSessionId}), never the raw client session id, so the note must go to that
 * same id or no turn will ever read it. The gate captured it in the request's replay envelope ({@code
 * memorySessionId}); a row recorded before that existed has tenant and subject in the envelope, from
 * which the id is derived the way {@code ChatTurnPreflight} derives it. With neither, no note is
 * written - never one under the raw id, which nothing reads - and the problem is reported.
 *
 * <p><b>Representation.</b> The note is an ASSISTANT-role turn, because the stored history has only
 * USER/ASSISTANT roles; the bracketed shape keeps it visibly distinct from model prose.
 */
final class ApprovalOutcomeNotes {

    static final String TRUNCATED_SUFFIX = "\n...[truncated]";

    private ApprovalOutcomeNotes() {
    }

    static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + TRUNCATED_SUFFIX;
    }

    /**
     * Skipped silently when the recorded session id is missing or is the synthetic placeholder (no real
     * conversation exists to inform). Any other failure is logged at WARN and returned as a short
     * description so the caller can tell the approver: it must not change the outcome of an approval that
     * already executed (or already failed for its own reason), but it is not harmless either - the agent
     * will not know the result and may ask for the action again.
     *
     * @return {@code null} when the note was written or there is no conversation to write it to,
     *     otherwise why it could not be written
     */
    static String append(
            ObjectProvider<SessionMemoryService> sessionMemoryService,
            ApprovalRequest request,
            ReplayTarget target,
            String note,
            Logger log) {
        String sessionId = target.sessionId();
        if (sessionId == null
                || sessionId.isBlank()
                || sessionId.startsWith(ReplayTarget.FALLBACK_SESSION_PREFIX)) {
            return null;
        }
        SessionMemoryService memory = sessionMemoryService.getIfAvailable();
        if (memory == null) {
            return null;
        }
        String memorySessionId = target.memorySessionIdOrDerived();
        if (memorySessionId == null) {
            log.warn(
                    "Approval request {} (agent {}, session '{}'): no scoped memory session id was recorded and none can "
                            + "be derived (no tenant/subject in the replay envelope) - the approval-outcome note was "
                            + "NOT written, so the agent's next turn will not see the result",
                    request.getId(),
                    target.agentId(),
                    sessionId);
            return "no conversation history could be identified";
        }
        try {
            memory.appendTurn(memorySessionId, ChatTurn.assistant(note));
            return null;
        } catch (RuntimeException e) {
            log.warn(
                    "Approval request {} (agent {}, session '{}', memory id {}): failed to append the approval-outcome "
                            + "note (approval outcome unaffected, but the agent's next turn will not see the result)",
                    request.getId(),
                    target.agentId(),
                    sessionId,
                    memorySessionId,
                    e);
            return "writing to conversation history failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
