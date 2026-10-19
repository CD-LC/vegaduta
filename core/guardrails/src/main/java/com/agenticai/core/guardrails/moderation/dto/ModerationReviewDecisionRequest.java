package com.agenticai.core.guardrails.moderation.dto;

import jakarta.validation.constraints.Size;

/**
 * Phase P6. Optional body for a reviewer's approve/block/escalate action -
 * free-text notes recorded on the item and in the audit trail. The reviewer's
 * identity is NOT taken from this body; it comes from the authenticated
 * principal server-side (see {@code ModerationReviewController}), so a caller
 * can't attribute a decision to someone else.
 *
 * @param notes optional reviewer notes (why they decided as they did)
 */
public record ModerationReviewDecisionRequest(
    @Size(max = 4000, message = "notes must be at most 4000 characters")
    String notes
) {
}
