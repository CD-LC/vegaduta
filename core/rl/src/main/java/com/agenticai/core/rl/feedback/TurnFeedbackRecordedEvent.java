package com.agenticai.core.rl.feedback;

import java.util.UUID;
import org.springframework.context.ApplicationEvent;

/**
 * Published after a person's thumbs/rating/comment on a turn is stored. It carries the facts only; a listener (the
 * preferences-from-behavior loop in agents-jobs) decides whether to use them. The comment is the person's own words
 * and is never logged by the publisher. Published on the request thread, so listeners must be quick.
 */
public final class TurnFeedbackRecordedEvent extends ApplicationEvent {

    private final transient String subject;
    private final transient UUID agentId;
    private final transient String sessionId;
    private final transient String comment;

    public TurnFeedbackRecordedEvent(
            Object source, String subject, UUID agentId, String sessionId, String comment) {
        super(source);
        this.subject = subject;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.comment = comment;
    }

    public String getSubject() {
        return subject;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getComment() {
        return comment;
    }
}
