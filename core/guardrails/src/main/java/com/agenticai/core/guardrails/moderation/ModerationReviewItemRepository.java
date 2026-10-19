package com.agenticai.core.guardrails.moderation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase P6. Repository for the per-tenant human review queue. Routed to the
 * calling tenant's schema by the same Hibernate multi-tenancy resolver every
 * other tenant-scoped repository uses (the {@link ModerationReviewItem} table
 * has no schema qualifier).
 */
public interface ModerationReviewItemRepository extends JpaRepository<ModerationReviewItem, UUID> {

    List<ModerationReviewItem> findByStatusOrderByCreatedAtDesc(ModerationReviewStatus status);

    List<ModerationReviewItem> findAllByOrderByCreatedAtDesc();
}
