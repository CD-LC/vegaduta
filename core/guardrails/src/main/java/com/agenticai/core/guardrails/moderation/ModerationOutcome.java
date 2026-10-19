package com.agenticai.core.guardrails.moderation;

import java.util.UUID;

/**
 * Phase P6. What {@link ModerationService#moderate} returns to its caller -
 * the final decision plus enough provenance for the caller to act and for a
 * human to later understand it.
 *
 * @param decision the final {@link ModerationDecision}
 * @param decidingLayer which layer drove the decision; null when {@code
 *     decision == ALLOW} because nothing fired
 * @param pack the policy pack that fired; null when nothing fired
 * @param confidence 0-100 confidence of the firing signal; 0 when nothing
 *     fired
 * @param reason short human-readable explanation, safe for general audit
 *     (never contains external-classifier detail - that stays on the
 *     restricted path)
 * @param reviewItemId the id of the {@link ModerationReviewItem} created when
 *     {@code decision == QUEUE_FOR_REVIEW}; null otherwise
 */
public record ModerationOutcome(
    ModerationDecision decision,
    ModerationLayer decidingLayer,
    ModerationPolicyPack pack,
    int confidence,
    String reason,
    UUID reviewItemId
) {

    /** The result when moderation is off, or on but nothing fired. */
    public static ModerationOutcome allow() {
        return new ModerationOutcome(ModerationDecision.ALLOW, null, null, 0, "no policy fired", null);
    }

    public static ModerationOutcome blocked(ModerationLayer layer, ModerationPolicyPack pack, int confidence, String reason) {
        return new ModerationOutcome(ModerationDecision.BLOCK, layer, pack, confidence, reason, null);
    }

    public static ModerationOutcome queued(ModerationLayer layer, ModerationPolicyPack pack, int confidence, String reason, UUID reviewItemId) {
        return new ModerationOutcome(ModerationDecision.QUEUE_FOR_REVIEW, layer, pack, confidence, reason, reviewItemId);
    }

    public boolean isBlocked() {
        return decision == ModerationDecision.BLOCK;
    }

    public boolean isAllowed() {
        return decision == ModerationDecision.ALLOW;
    }

    public boolean isQueuedForReview() {
        return decision == ModerationDecision.QUEUE_FOR_REVIEW;
    }
}
