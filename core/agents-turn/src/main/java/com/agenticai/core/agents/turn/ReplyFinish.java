package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.turn.TurnReplyOutcome.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * The parts of finish-reason handling both reasoning paths share ({@link FastStreamTurn} streams,
 * {@link BufferedTurnCalls} blocks): what text a non-answer outcome shows the person, what is stored
 * in the conversation for it, and the prompt of a pass that continues or retries a reply. The policy
 * values (nudges, notices, limits) are {@link ChatFinishProperties}.
 */
final class ReplyFinish {

    /** Separates the model's text from a platform-written notice that follows it. */
    private static final String NOTICE_SEPARATOR = "\n\n";

    /** Registry keys of the notices the person reads ({@code prompts/en/message.finish.*.md}). */
    static final String TRUNCATED_KEY = "message.finish.truncated";

    static final String REFUSAL_KEY = "message.finish.refusal";
    static final String MALFORMED_TOOL_CALL_KEY = "message.finish.malformed_tool_call";
    static final String EMPTY_REPLY_KEY = "message.finish.empty_reply";

    private ReplyFinish() {}

    /**
     * What the person is told for {@code state}, or {@code null} for an outcome that has no notice (a
     * finished answer, or a reply closed after a failure, which carries its own marker). Read from the
     * prompt registry for the turn's agent and tenant ({@code prompts}); with none (a hand-built fixture) the
     * {@link ChatFinishProperties} values, which default to the shipped texts.
     */
    static String notice(
            ChatFinishProperties cfg, TurnReplyOutcome.State state, ResolvedDirectives prompts) {
        if (prompts == null) {
            return notice(cfg, state);
        }
        return switch (state.kind()) {
            case TRUNCATED -> prompts.textOrShipped(TRUNCATED_KEY);
            case REFUSED ->
                    prompts.textOrShipped(
                            REFUSAL_KEY,
                            Map.of("reason", state.detail() == null ? "" : state.detail().strip()));
            case MALFORMED_TOOL_CALL -> prompts.textOrShipped(MALFORMED_TOOL_CALL_KEY);
            case EMPTY -> prompts.textOrShipped(EMPTY_REPLY_KEY);
            case COMPLETE, INTERRUPTED -> null;
        };
    }

    /** The {@link ChatFinishProperties} (deprecated seed) values; see {@link #notice(ChatFinishProperties, TurnReplyOutcome.State, ResolvedDirectives)}. */
    static String notice(ChatFinishProperties cfg, TurnReplyOutcome.State state) {
        return switch (state.kind()) {
            case TRUNCATED -> cfg.getTruncatedNotice();
            case REFUSED -> cfg.renderRefusalNotice(state.detail());
            case MALFORMED_TOOL_CALL -> cfg.getMalformedToolCallNotice();
            case EMPTY -> cfg.getEmptyReplyMessage();
            case COMPLETE, INTERRUPTED -> null;
        };
    }

    /** {@code body}, then {@code notice} after a blank line; just the notice when there is no body. */
    static String join(String body, String notice) {
        return body == null || body.isBlank() ? notice : body + NOTICE_SEPARATOR + notice;
    }

    /** What precedes a notice appended to text already emitted: nothing when nothing was emitted. */
    static String separator(boolean anyTextEmitted) {
        return anyTextEmitted ? NOTICE_SEPARATOR : "";
    }

    /**
     * The conversation entry for a reply that is not an answer. Text the model did produce is kept
     * and marked as cut off ({@link ChatTurn#INTERRUPTED_REPLY_SUFFIX}); when there is none the entry
     * is a failed reply carrying the notice, which the model is not shown again on later turns.
     * {@code Kind.INTERRUPTED} text already ends with its marker and is stored as it is.
     */
    static ChatTurn storedTurn(Kind kind, String body, String notice, String fullText) {
        if (kind == Kind.INTERRUPTED) {
            return ChatTurn.assistant(fullText);
        }
        if (body == null || body.isBlank()) {
            return ChatTurn.failedAssistant(notice);
        }
        return ChatTurn.assistant(body + ChatTurn.INTERRUPTED_REPLY_SUFFIX);
    }

    /** The prompt of a pass that continues {@code replySoFar} (or retries when there is none). */
    record PassPrompt(List<Message> messages, String user) {}

    /**
     * History, the user's message and what the model wrote so far, then {@code nudge} as the next user
     * message. With nothing written yet there is no assistant turn to show (providers reject an empty
     * one), so the nudge is appended to the user's message instead.
     */
    static PassPrompt passPrompt(List<Message> history, String userMessage, String replySoFar, String nudge) {
        if (replySoFar == null || replySoFar.isBlank()) {
            return new PassPrompt(history, userMessage + NOTICE_SEPARATOR + nudge);
        }
        List<Message> next = new ArrayList<>(history);
        next.add(new UserMessage(userMessage));
        next.add(new AssistantMessage(replySoFar));
        return new PassPrompt(next, nudge);
    }
}
