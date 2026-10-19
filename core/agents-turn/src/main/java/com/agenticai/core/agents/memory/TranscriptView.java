package com.agenticai.core.agents.memory;

import java.time.Instant;

/**
 * How a stored {@link ChatTurn} is shown to a person reloading a conversation.
 *
 * <p>The transcript keeps the outcome of a turn as an in-text marker ({@link ChatTurn#FAILED_REPLY_PREFIX},
 * {@link ChatTurn#INTERRUPTED_REPLY_SUFFIX}) because a turn has no status column. Every API that returns history
 * (the transcript endpoint, the branch path, the resumable-turn reply, exports) maps through here so the web gets
 * an explicit {@code status} and clean text, and never has to know the technical marker:
 * <ul>
 *   <li>{@code ok} - an ordinary message;</li>
 *   <li>{@code failed} - the reply failed before producing text; {@code content} is the message the person was shown
 *       ("The AI provider rejected our credentials..."), {@code error} is true;</li>
 *   <li>{@code interrupted} - the reply was cut off; {@code content} is the partial text without the marker.</li>
 * </ul>
 */
public final class TranscriptView {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_INTERRUPTED = "interrupted";

    private TranscriptView() {
    }

    /** One transcript message as the API returns it. {@code error} is true exactly when {@code status} is failed. */
    public record Entry(String role, String content, Instant timestamp, String status, boolean error) {
    }

    /** {@code ok} | {@code failed} | {@code interrupted} for a stored message's text and role. */
    public static String statusOf(MessageRole role, String content) {
        if (role != MessageRole.ASSISTANT || content == null) {
            return STATUS_OK;
        }
        if (content.startsWith(ChatTurn.FAILED_REPLY_PREFIX)) {
            return STATUS_FAILED;
        }
        if (content.endsWith(ChatTurn.INTERRUPTED_REPLY_SUFFIX)) {
            return STATUS_INTERRUPTED;
        }
        return STATUS_OK;
    }

    /** The text to display: the technical marker removed. */
    public static String textOf(MessageRole role, String content) {
        if (content == null) {
            return "";
        }
        return switch (statusOf(role, content)) {
            case STATUS_FAILED -> content.substring(ChatTurn.FAILED_REPLY_PREFIX.length()).strip();
            case STATUS_INTERRUPTED -> content.substring(0, content.length() - ChatTurn.INTERRUPTED_REPLY_SUFFIX.length());
            default -> content;
        };
    }

    public static Entry of(ChatTurn turn) {
        String status = statusOf(turn.role(), turn.content());
        return new Entry(
            turn.role() == null ? null : turn.role().name().toLowerCase(java.util.Locale.ROOT),
            textOf(turn.role(), turn.content()),
            turn.timestamp(),
            status,
            STATUS_FAILED.equals(status));
    }
}
