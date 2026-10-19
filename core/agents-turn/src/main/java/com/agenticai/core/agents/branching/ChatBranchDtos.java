package com.agenticai.core.agents.branching;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire types for the chat-branching routes (see {@link ChatBranchController}). */
public final class ChatBranchDtos {

    private ChatBranchDtos() {
    }

    /** {@code enabled} mirrors app.chat.branching.enabled; the web only renders the edit / regenerate / switch controls when true. */
    public record Capabilities(boolean enabled) {
    }

    /**
     * One message of the active path. {@code siblingIndex} is 0-based and {@code siblingCount} counts
     * this message too, so the UI shows "siblingIndex + 1 / siblingCount" and offers the switcher only
     * when the count is above 1. {@code role} is "user" or "assistant".
     *
     * <p>{@code status} is "ok", "failed" (the reply failed before any text: {@code content} is the message the
     * person was shown, {@code error} is true) or "interrupted" (a cut-off reply: {@code content} is the partial
     * text). The technical marker is never in {@code content}.
     */
    public record MessageView(
        UUID id,
        UUID parentId,
        String role,
        String content,
        Instant createdAt,
        int siblingIndex,
        int siblingCount,
        String status,
        boolean error
    ) {
    }

    /** The path currently shown and replayed to the model, oldest first. {@code activeLeafId} is null for an empty path. */
    public record ActivePathResponse(String sessionId, UUID activeLeafId, List<MessageView> messages) {
    }

    /** The versions of one message in order; {@code index} is 0-based, {@code count} includes the message itself. */
    public record SiblingsResponse(UUID messageId, List<UUID> ids, int index, int count) {
    }

    /** Switch to the version {@code messageId}; the server lands on the newest leaf beneath it. */
    public record SwitchLeafRequest(@NotNull UUID messageId) {
    }

    /**
     * New text for an earlier user message. {@code turnRef} / {@code stepEvents} are the same
     * optional fields a normal chat turn carries.
     */
    public record EditRequest(
        @NotBlank(message = "message is required")
        @Size(max = 8000, message = "message is too long (8000 character maximum)")
        String message,
        String turnRef,
        boolean stepEvents
    ) {
    }

    /** Optional body of a regenerate call; the user text is the one already stored. */
    public record RegenerateRequest(String turnRef, boolean stepEvents) {
    }
}
