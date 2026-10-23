package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import com.agenticai.core.agents.turn.ToolLedgerSummary;
import com.agenticai.core.approval.ApprovalRequest;
import java.util.List;
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
 * <p><b>Representation.</b> The note is a platform tool record ({@link ToolLedger}, one entry: the tool, its
 * arguments, the bracketed note as the result, OK / FAILED / NOT_RUN, and the request and who decided it) on
 * an ASSISTANT turn with no text ({@link ChatTurn#toolRecord}). The model reads it on every later turn - the
 * replay folds a record-only turn's record into the text it is sent - while the transcript the console
 * restores, the export and the resumed-reply reader skip the turn, so a person never sees raw tool output
 * presented as something the assistant said. The note's output and the arguments are masked for credentials
 * and cut like every other tool record ({@link ToolLedgerSummary}).
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
     * The record of a decided action: {@code status} OK (approved, ran), FAILED (approved, failed) or NOT_RUN
     * (denied); {@code shown} is the output, failure or reason, masked and cut to {@code
     * max-note-result-chars} before it is put into the configured note wording.
     */
    static ToolLedger outcomeRecord(
            ApprovalContinuationProperties texts,
            ApprovalRequest request,
            ReplayTarget target,
            ToolLedger.Status status,
            String decidedBy,
            String shown) {
        ToolLedgerProperties limits = new ToolLedgerProperties();
        limits.setMaxArgumentChars(texts.getMaxNoteArgumentsChars());
        limits.setMaxResultChars(texts.getMaxNoteResultChars());
        ToolLedger.Entry cut =
                ToolLedgerSummary.entry(
                        target.toolName(), target.argumentsJson(), shown, status, null, limits, null);
        String note =
                switch (status) {
                    case FAILED -> texts.renderFailedNote(target.toolName(), cut.result());
                    case NOT_RUN -> texts.renderDeniedNote(target.toolName(), decidedBy, cut.result());
                    default -> texts.renderCompletedNote(target.toolName(), cut.result());
                };
        return new ToolLedger(
                List.of(
                        new ToolLedger.Entry(
                                cut.tool(),
                                cut.arguments(),
                                note,
                                status,
                                texts.renderNoteApproval(request.getId(), decidedBy))),
                0);
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
            ToolLedger record,
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
            memory.appendTurn(memorySessionId, ChatTurn.toolRecord(record));
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
