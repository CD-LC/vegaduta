package com.agenticai.core.guardrails.moderation;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase P6. The human review queue's read/act service. Creates queue items
 * (called by {@link ModerationService}) and records reviewer decisions -
 * approve / block / escalate - every one of which is written to {@link
 * AuditLogService}. There is no un-audited path to resolve an item, which is
 * the P6 requirement ("every decision audited").
 */
@Service
public class ModerationReviewService {

    static final String ACTION_QUEUED = "moderation.review.queued";
    static final String ACTION_APPROVE = "moderation.review.approve";
    static final String ACTION_BLOCK = "moderation.review.block";
    static final String ACTION_ESCALATE = "moderation.review.escalate";
    static final String TARGET_TYPE = "moderation_review_item";

    private final ModerationReviewItemRepository repository;
    private final AuditLogService auditLogService;

    public ModerationReviewService(ModerationReviewItemRepository repository, AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    /**
     * Enqueues content for human review and audits the enqueue. Called from
     * {@link ModerationService} when a signal lands in the review band.
     */
    @Transactional
    public ModerationReviewItem enqueue(
        ModerationContext context,
        String content,
        ModerationPolicyPack pack,
        ModerationLayer layer,
        int confidence,
        String reason
    ) {
        ModerationReviewItem item = new ModerationReviewItem(
            context != null ? context.tenantId() : null,
            context != null ? context.agentId() : null,
            context != null ? context.sessionId() : null,
            content,
            pack,
            layer,
            confidence,
            reason,
            context != null ? context.direction() : null
        );
        ModerationReviewItem saved = repository.save(item);
        auditLogService.record(
            context != null ? context.actorId() : null,
            ACTION_QUEUED,
            TARGET_TYPE,
            saved.getId().toString(),
            AuditOutcome.PENDING_APPROVAL,
            "Queued for review: pack=" + pack.configKey() + " layer=" + layer + " confidence=" + confidence + " (" + reason + ")"
        );
        return saved;
    }

    public List<ModerationReviewItem> list(ModerationReviewStatus status) {
        return status != null
            ? repository.findByStatusOrderByCreatedAtDesc(status)
            : repository.findAllByOrderByCreatedAtDesc();
    }

    public ModerationReviewItem get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ModerationReviewNotFoundException(id));
    }

    @Transactional
    public ModerationReviewItem approve(UUID id, String reviewedBy, String notes) {
        return decide(id, ModerationReviewStatus.APPROVED, ACTION_APPROVE, AuditOutcome.APPROVED, reviewedBy, notes);
    }

    @Transactional
    public ModerationReviewItem block(UUID id, String reviewedBy, String notes) {
        return decide(id, ModerationReviewStatus.BLOCKED, ACTION_BLOCK, AuditOutcome.REJECTED, reviewedBy, notes);
    }

    @Transactional
    public ModerationReviewItem escalate(UUID id, String reviewedBy, String notes) {
        return decide(id, ModerationReviewStatus.ESCALATED, ACTION_ESCALATE, AuditOutcome.PENDING_APPROVAL, reviewedBy, notes);
    }

    private ModerationReviewItem decide(
        UUID id,
        ModerationReviewStatus newStatus,
        String actionType,
        AuditOutcome outcome,
        String reviewedBy,
        String notes
    ) {
        ModerationReviewItem item = repository.findById(id).orElseThrow(() -> new ModerationReviewNotFoundException(id));
        if (!item.isPending()) {
            throw new ModerationReviewAlreadyDecidedException(id, item.getStatus());
        }
        item.resolve(newStatus, reviewedBy, notes);
        ModerationReviewItem saved = repository.save(item);
        auditLogService.record(
            reviewedBy,
            actionType,
            TARGET_TYPE,
            id.toString(),
            outcome,
            "Reviewer decision on pack=" + item.getPolicyPack() + " confidence=" + item.getConfidence()
                + (notes != null && !notes.isBlank() ? " - notes: " + notes : "")
        );
        return saved;
    }
}
