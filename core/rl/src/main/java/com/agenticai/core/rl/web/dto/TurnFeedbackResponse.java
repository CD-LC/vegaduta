package com.agenticai.core.rl.web.dto;

import com.agenticai.core.rl.domain.FeedbackSignal;
import com.agenticai.core.rl.domain.TurnFeedback;
import java.time.Instant;
import java.util.UUID;

/** The persisted {@link TurnFeedback} echoed back to the caller. */
public record TurnFeedbackResponse(
    UUID id,
    UUID agentId,
    String sessionId,
    String turnRef,
    FeedbackSignal signal,
    Short rating,
    String comment,
    String editedAnswer,
    Instant createdAt
) {

    public static TurnFeedbackResponse from(TurnFeedback f) {
        return new TurnFeedbackResponse(
            f.getId(),
            f.getAgentId(),
            f.getSessionId(),
            f.getTurnRef(),
            f.getSignal(),
            f.getRating(),
            f.getComment(),
            f.getEditedAnswer(),
            f.getCreatedAt()
        );
    }
}
