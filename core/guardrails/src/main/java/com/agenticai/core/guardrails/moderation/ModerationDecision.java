package com.agenticai.core.guardrails.moderation;

/**
 * Phase P6. The verdict {@link ModerationService} reaches for a single piece
 * of content. Deliberately three-valued, not a block/allow boolean: the
 * middle state ({@link #QUEUE_FOR_REVIEW}) is the whole point of a
 * layered, human-reviewable design - content in the confidence band between
 * "clearly fine" and "clearly disallowed" is escalated to a trust-&-safety
 * reviewer rather than auto-decided either way.
 */
public enum ModerationDecision {

    /** Nothing fired above the review band - content proceeds unchanged. */
    ALLOW,

    /**
     * Content scored at or above the block band for at least one enabled
     * pack - withheld. Always paired with an audit entry; P6 forbids a
     * silent block.
     */
    BLOCK,

    /**
     * Content landed in the review band - queued for a human reviewer as a
     * {@link ModerationReviewItem}, NOT auto-decided. The caller should treat
     * this as "hold, pending review" rather than either allow or block.
     */
    QUEUE_FOR_REVIEW
}
