package com.agenticai.core.templates;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link CommunityTemplate}. A control-plane (cross-tenant)
 * table, so - unlike the per-tenant repositories elsewhere in this codebase -
 * its queries are NOT implicitly tenant-scoped; ownership/visibility scoping is
 * done explicitly by {@link CommunityTemplateService}.
 *
 * <p>The gallery filters are deliberately DERIVED query methods branched in the
 * service, not one {@code (:param IS NULL OR ...)} JPQL query - that pattern hit
 * a Postgres/Hibernate parameter-type-inference bug in this codebase before (see
 * {@code AuditLogRepository}'s note, which moved to a Specification for the same
 * reason). Approved rows always carry a {@code reviewedAt} (set on approve), so
 * ordering by it descending is well-defined without a NULLS-ordering clause.
 */
public interface CommunityTemplateRepository extends JpaRepository<CommunityTemplate, UUID> {

    /** Public gallery, no filter - APPROVED only, most-recently-reviewed first. */
    List<CommunityTemplate> findByStatusOrderByReviewedAtDesc(TemplateStatus status, Pageable pageable);

    /**
     * Sortable gallery finders - the {@code sort} marketplace parameter
     * (recent/imports/rating) supplies a {@code Sort} inside the Pageable, so
     * these deliberately carry no ORDER BY in the method name. Default sort is
     * reviewedAt DESC (see the service).
     */
    List<CommunityTemplate> findByStatus(TemplateStatus status, Pageable pageable);

    List<CommunityTemplate> findByStatusAndKind(TemplateStatus status, TemplateKind kind, Pageable pageable);

    List<CommunityTemplate> findByStatusAndCategory(TemplateStatus status, String category, Pageable pageable);

    List<CommunityTemplate> findByStatusAndKindAndCategory(
        TemplateStatus status, TemplateKind kind, String category, Pageable pageable
    );

    /** Public gallery filtered by kind. */
    List<CommunityTemplate> findByStatusAndKindOrderByReviewedAtDesc(
        TemplateStatus status, TemplateKind kind, Pageable pageable
    );

    /** Public gallery filtered by category. */
    List<CommunityTemplate> findByStatusAndCategoryOrderByReviewedAtDesc(
        TemplateStatus status, String category, Pageable pageable
    );

    /** Public gallery filtered by kind AND category. */
    List<CommunityTemplate> findByStatusAndKindAndCategoryOrderByReviewedAtDesc(
        TemplateStatus status, TemplateKind kind, String category, Pageable pageable
    );

    /** One approved template by id - the public detail endpoint's only loader (never returns a non-approved row). */
    Optional<CommunityTemplate> findByIdAndStatus(UUID id, TemplateStatus status);

    /** The caller's own submissions across every status, newest first. */
    List<CommunityTemplate> findBySubmittedByOrderByUpdatedAtDesc(String submittedBy);

    /** The moderation queue - everything awaiting review, oldest submission first (FIFO). */
    List<CommunityTemplate> findByStatusOrderBySubmittedAtAsc(TemplateStatus status);

    /**
     * Admin enumeration of one status (e.g. APPROVED, so an admin can pick a live
     * template to withdraw), most-recently-reviewed first. Distinct from the PUBLIC
     * gallery finders above: this returns the full owner/admin projection (submitter,
     * source tenant, moderation trail), which the public endpoint never exposes.
     */
    List<CommunityTemplate> findByStatusOrderByReviewedAtDesc(TemplateStatus status);

    /** Per-tenant-per-window submission count, for the abuse cap (see the service). */
    long countBySourceTenantIdAndCreatedAtAfter(String sourceTenantId, Instant after);
}
