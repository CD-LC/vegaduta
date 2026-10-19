package com.agenticai.core.guardrails.moderation;

/**
 * Phase P6. Lifecycle of a {@link ModerationReviewItem} in the human review
 * queue. A queued item starts {@link #PENDING}; a trust-&-safety reviewer
 * moves it to exactly one terminal state, and every transition is audited.
 */
public enum ModerationReviewStatus {

    /** Awaiting a human decision. The only non-terminal state. */
    PENDING,

    /** Reviewer judged the content acceptable - it may proceed. */
    APPROVED,

    /** Reviewer judged the content disallowed - it stays withheld. */
    BLOCKED,

    /**
     * Reviewer escalated beyond first-line review (e.g. to a senior T&S
     * lead, legal, or - for a suspected highest-stakes category - the
     * restricted-handling path). Terminal for the first-line queue; the item
     * remains a durable record of the escalation.
     */
    ESCALATED
}
