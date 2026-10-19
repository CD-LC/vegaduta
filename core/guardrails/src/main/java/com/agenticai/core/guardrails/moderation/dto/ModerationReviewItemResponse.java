package com.agenticai.core.guardrails.moderation.dto;

import com.agenticai.core.guardrails.moderation.ModerationReviewItem;
import com.agenticai.core.guardrails.moderation.ModerationReviewStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase P6. Read model for a review-queue item returned by {@code
 * ModerationReviewController}. Exposes the full flagged content to the
 * authenticated trust-&-safety reviewer - that is the point of the queue -
 * but nothing here ever carries external-classifier (restricted) detail,
 * since those never become review-queue items.
 */
public record ModerationReviewItemResponse(
    UUID id,
    String tenantId,
    UUID agentId,
    String sessionId,
    String content,
    String policyPack,
    String decidingLayer,
    int confidence,
    ModerationReviewStatus status,
    String reason,
    String direction,
    Instant createdAt,
    String reviewedBy,
    Instant reviewedAt,
    String reviewNotes
) {

    public static ModerationReviewItemResponse from(ModerationReviewItem item) {
        return new ModerationReviewItemResponse(
            item.getId(),
            item.getTenantId(),
            item.getAgentId(),
            item.getSessionId(),
            item.getContent(),
            item.getPolicyPack(),
            item.getDecidingLayer(),
            item.getConfidence(),
            item.getStatus(),
            item.getReason(),
            item.getDirection(),
            item.getCreatedAt(),
            item.getReviewedBy(),
            item.getReviewedAt(),
            item.getReviewNotes()
        );
    }
}
