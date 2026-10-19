package com.agenticai.core.rl.web.dto;

import com.agenticai.core.rl.domain.FeedbackSignal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The per-turn feedback payload the web Playground's {@code TurnFeedback.tsx}
 * posts to {@code POST /api/agents/{agentId}/turns/feedback}. {@link #signal}
 * deserializes directly onto {@link FeedbackSignal} (the enum names match the
 * frontend's {@code THUMBS_UP | THUMBS_DOWN | RATING | EDIT | RESOLVED |
 * UNRESOLVED} exactly). {@link #sessionId} is optional (the control sends it
 * only when a chat session is active); {@link #rating} is only meaningful for
 * {@link FeedbackSignal#RATING} and, when present, must be 1-5.
 */
public record TurnFeedbackRequest(
    String sessionId,
    @NotBlank String turnRef,
    @NotNull FeedbackSignal signal,
    @Min(1) @Max(5) Short rating,
    String comment,
    String editedAnswer
) {
}
