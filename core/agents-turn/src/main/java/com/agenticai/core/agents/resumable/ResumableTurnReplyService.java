package com.agenticai.core.agents.resumable;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TranscriptView;
import com.agenticai.core.agents.resumable.ResumableStreamBuffer.TurnMeta;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The persisted-reply fallback of resumable chat streams. When a resume cannot be served from the bounded event
 * buffer (it was trimmed past the client's cursor, 410, or it expired / lives on another replica, 404) the turn has
 * normally finished and {@code SessionMemoryService} already holds the assistant message, so the client can still show
 * the real reply instead of an error.
 *
 * <p>Authorisation is the stream service's own (same tenant AND same subject that started the turn, and RUNNER on the
 * agent still held), so a session id alone never reads a conversation; the session is the one the server recorded for
 * the turn, never one the caller names. Because the lookup goes through the turn's record it lives as long as that
 * record ({@code app.chat.resumable-streams.ttl-seconds}).
 */
@Service
public class ResumableTurnReplyService {

    static final String STATUS_COMPLETE = "complete";
    static final String STATUS_PENDING = "pending";
    /** The turn's reply failed before producing text; {@code text} is the message the person was shown. */
    static final String STATUS_FAILED = "failed";

    private final ResumableChatStreamService streams;
    private final SessionMemoryService sessions;

    public ResumableTurnReplyService(ResumableChatStreamService streams, SessionMemoryService sessions) {
        this.streams = streams;
        this.sessions = sessions;
    }

    /**
     * {@code text} is null while {@code status} is {@code pending} (no assistant message stored for this turn yet).
     * {@code status} {@code failed} means the reply failed before producing text: {@code text} is then the message the
     * person was shown (technical marker removed) and {@code error} is true. A cut-off reply stays {@code complete}
     * with its partial text, as before.
     */
    public record PersistedReply(String turnId, String status, String text, boolean error) {
        public PersistedReply(String turnId, String status, String text) {
            this(turnId, status, text, STATUS_FAILED.equals(status));
        }
    }

    public PersistedReply persistedReply(String turnId) {
        TurnMeta meta = streams.authorizedMeta(turnId);
        return pick(turnId, meta.startedAtMs(), sessions.getFullHistory(meta.sessionId()));
    }

    /**
     * The message the person was shown when this turn's reply failed with no text, or null when the turn did not fail
     * (or the transcript no longer holds it). Read from the stored transcript rather than from the stream buffer, so
     * it survives the buffer's own expiry and a resume served by another replica.
     */
    public String failureMessage(String turnId) {
        PersistedReply reply = persistedReply(turnId);
        return STATUS_FAILED.equals(reply.status()) ? reply.text() : null;
    }

    /**
     * The newest assistant message written at or after the turn started. An older one belongs to an earlier turn of
     * the same session and must never be passed off as this turn's reply.
     */
    static PersistedReply pick(String turnId, long startedAtMs, List<ChatTurn> history) {
        if (history != null) {
            Instant startedAt = Instant.ofEpochMilli(startedAtMs);
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatTurn turn = history.get(i);
                // A record-only turn (a decided action's outcome) is not anyone's reply.
                if (turn.role() != MessageRole.ASSISTANT || ChatTurn.isToolRecordOnly(turn)) {
                    continue;
                }
                if (turn.timestamp() != null && !turn.timestamp().isBefore(startedAt)
                    && turn.content() != null && !turn.content().isBlank()) {
                    if (ChatTurn.isFailedReply(turn)) {
                        return new PersistedReply(turnId, STATUS_FAILED,
                            TranscriptView.textOf(turn.role(), turn.content()), true);
                    }
                    return new PersistedReply(turnId, STATUS_COMPLETE, turn.content(), false);
                }
                break;
            }
        }
        return new PersistedReply(turnId, STATUS_PENDING, null, false);
    }
}
