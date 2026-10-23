package com.agenticai.core.agents.memory;

import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.prompts.PromptResolver;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * One turn of conversation, as stored in Redis.
 *
 * <p>{@code tools} is the compact record of what the turn's tools did ({@link ToolLedger}: tool,
 * arguments summary, result summary, status, approval state), set on an assistant turn that called
 * tools and {@code null} on every other turn - so a turn stored before the ledger existed, and a
 * tool-free turn, read and write exactly as they always did. It is kept apart from {@code content} on
 * purpose: the transcript, the export and the failed/interrupted markers all work on {@code content},
 * and only the model-facing replay ({@code SessionMemoryService#getHistory}) folds the ledger into the
 * text. Full tool results stay in the tool-call log; this is the part the model needs on the next turn.
 */
public record ChatTurn(
        MessageRole role,
        String content,
        Instant timestamp,
        @JsonInclude(JsonInclude.Include.NON_NULL) ToolLedger tools) {

    public ChatTurn {
        tools = ToolLedger.nullIfNone(tools);
    }

    /** A turn without a tool ledger: every turn that did not call tools. */
    public ChatTurn(MessageRole role, String content, Instant timestamp) {
        this(role, content, timestamp, null);
    }

    public static ChatTurn user(String content) {
        return new ChatTurn(MessageRole.USER, content, Instant.now());
    }

    public static ChatTurn assistant(String content) {
        return new ChatTurn(MessageRole.ASSISTANT, content, Instant.now());
    }

    /** An assistant turn that called tools: {@code tools} is what they did ({@code null}/empty = none). */
    public static ChatTurn assistant(String content, ToolLedger tools) {
        return new ChatTurn(MessageRole.ASSISTANT, content, Instant.now(), tools);
    }

    /** This turn with {@code ledger} as its tool record; {@code null}/empty keeps it without one. */
    public ChatTurn withTools(ToolLedger ledger) {
        return new ChatTurn(role, content, timestamp, ledger);
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
        return failedAssistant(message, null);
    }

    /**
     * As {@link #failedAssistant(String)}, with {@code fallback} as the sentence stored when {@code
     * message} is blank (the registry's {@code message.generic_failure} for the agent); {@code null}
     * = the shipped wording.
     */
    public static ChatTurn failedAssistant(String message, String fallback) {
        String reason = message == null || message.isBlank()
            ? (fallback != null && !fallback.isBlank() ? fallback.strip() : defaultFailureMessage())
            : message.strip();
        return new ChatTurn(MessageRole.ASSISTANT, FAILED_REPLY_PREFIX + reason, Instant.now());
    }

    private static String defaultFailureMessage() {
        return PromptResolver.defaultsOnly().catalog().defaultText("message.generic_failure");
    }

    /** True for a {@link #failedAssistant} entry. Static so it adds no property to the stored JSON. */
    public static boolean isFailedReply(ChatTurn turn) {
        return turn != null && turn.role() == MessageRole.ASSISTANT
            && turn.content() != null && turn.content().startsWith(FAILED_REPLY_PREFIX);
    }
}
