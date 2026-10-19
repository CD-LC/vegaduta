package com.agenticai.core.agents.memory;

import java.time.Instant;

/**
 * One turn of conversation, as stored in Redis. Kept deliberately minimal —
 * no tool-call metadata yet, that lands with Stage 4's tool-call logging.
 */
public record ChatTurn(MessageRole role, String content, Instant timestamp) {

    public static ChatTurn user(String content) {
        return new ChatTurn(MessageRole.USER, content, Instant.now());
    }

    public static ChatTurn assistant(String content) {
        return new ChatTurn(MessageRole.ASSISTANT, content, Instant.now());
    }

    /**
     * Starts the content of an assistant turn that stands for a reply that
     * FAILED before producing any text: the rest of the content is the
     * message the person was shown ("The AI provider rejected our
     * credentials..."). A turn carries no status field (and the branching
     * table that mirrors it has only role + content), so - like the
     * {@code [reply interrupted]} suffix on a cut-off reply - an in-text
     * marker is how the transcript keeps the outcome. Transcript readers
     * (session reload, export) show it; {@code SessionMemoryService#getHistory}
     * leaves it out of what is replayed to the model, which must not read an
     * error message as something it said.
     */
    public static final String FAILED_REPLY_PREFIX = "[reply failed] ";

    /** Ends an assistant turn whose reply was cut off after some text; see {@link TranscriptView}. */
    public static final String INTERRUPTED_REPLY_SUFFIX = "\n\n[reply interrupted]";

    /** The transcript entry for a reply that failed with {@code message} and no text. */
    public static ChatTurn failedAssistant(String message) {
        String reason = message == null || message.isBlank()
            ? "Something went wrong finishing this reply. Please try again." : message.strip();
        return new ChatTurn(MessageRole.ASSISTANT, FAILED_REPLY_PREFIX + reason, Instant.now());
    }

    /** True for a {@link #failedAssistant} entry. Static so it adds no property to the stored JSON. */
    public static boolean isFailedReply(ChatTurn turn) {
        return turn != null && turn.role() == MessageRole.ASSISTANT
            && turn.content() != null && turn.content().startsWith(FAILED_REPLY_PREFIX);
    }
}
