package com.agenticai.core.platform.moderation;

import com.agenticai.core.guardrails.moderation.ModerationReviewItem;
import com.agenticai.core.guardrails.moderation.ModerationReviewStatus;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Review-queue and blocklist operations behind {@link ModerationController}, returning response
 * DTOs so the controller never handles the engine's {@link ModerationReviewItem} or the {@link
 * ModerationBlocklistEntry} entities.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class ModerationConsoleService {

    private final PlatformModerationQueueService reviewQueue;
    private final ModerationBlocklistService blocklists;

    public ModerationConsoleService(
            PlatformModerationQueueService reviewQueue, ModerationBlocklistService blocklists) {
        this.reviewQueue = reviewQueue;
        this.blocklists = blocklists;
    }

    public List<ReviewItemResponse> listReviewItems(ModerationReviewStatus status) {
        return reviewQueue.list(status).stream()
                .map(t -> toResponse(t.tenantSchema(), t.item()))
                .toList();
    }

    public long pendingCount() {
        return reviewQueue.pendingCount();
    }

    public ReviewItemResponse approve(String tenantId, UUID id, String reviewedBy, String notes) {
        return toResponse(tenantId, reviewQueue.approve(tenantId, id, reviewedBy, notes));
    }

    public ReviewItemResponse block(String tenantId, UUID id, String reviewedBy, String notes) {
        return toResponse(tenantId, reviewQueue.block(tenantId, id, reviewedBy, notes));
    }

    public ReviewItemResponse escalate(String tenantId, UUID id, String reviewedBy, String notes) {
        return toResponse(tenantId, reviewQueue.escalate(tenantId, id, reviewedBy, notes));
    }

    public List<BlocklistEntryResponse> listBlocklist(ModerationBlocklistType type) {
        return blocklists.list(type).stream().map(ModerationConsoleService::toResponse).toList();
    }

    public BlocklistEntryResponse upsertBlocklistEntry(
            ModerationBlocklistType type,
            String value,
            boolean active,
            String reason,
            String actor) {
        return toResponse(blocklists.upsert(type, value, active, reason, actor));
    }

    private static ReviewItemResponse toResponse(String tenantSchema, ModerationReviewItem i) {
        return new ReviewItemResponse(
                i.getId(),
                tenantSchema,
                i.getPolicyPack(),
                i.getDecidingLayer(),
                i.getConfidence(),
                i.getReason(),
                i.getContent(),
                i.getDirection(),
                i.getStatus().name(),
                i.getCreatedAt() != null ? i.getCreatedAt().toString() : null,
                i.getReviewedBy(),
                i.getReviewedAt() != null ? i.getReviewedAt().toString() : null,
                i.getReviewNotes());
    }

    private static BlocklistEntryResponse toResponse(ModerationBlocklistEntry e) {
        return new BlocklistEntryResponse(
                e.getId(),
                e.getEntryType().name(),
                e.getValue(),
                e.getReason(),
                e.isActive(),
                e.getCreatedBy(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
    }
}
