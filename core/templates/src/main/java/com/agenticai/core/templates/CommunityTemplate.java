package com.agenticai.core.templates;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A community template: a sanitised, declarative snapshot of an agent's or workflow's
 * <em>design</em>, submitted for moderation and (once approved) publicly browsable and importable.
 * Maps to {@code control_plane.community_template} (migration {@code
 * V156__community_template.sql}).
 *
 * <p><b>Control-plane (cross-tenant) by nature</b>, unlike per-tenant {@code agent}/ {@code
 * workflow_definition} - an approved template is visible to the whole world, so it cannot live in
 * one tenant's schema. {@code sourceTenantId} records which tenant a template originated from (for
 * admin traceability + abuse follow-up), but is NEVER part of {@link #configJson} and never
 * surfaced on the public gallery.
 *
 * <p><b>{@link #configJson} is an ALLOW-LIST snapshot</b> built server-side by {@link
 * TemplateSnapshotService} - it contains only known-safe design fields and, by construction, no
 * provider keys, channel secrets, tenant ids, PII, knowledge documents, or {@code created_by}
 * subjects. That property is the whole point of the feature; see {@link TemplateSnapshotService}
 * and its test.
 */
@Entity
@Table(name = "community_template", schema = "control_plane")
public class CommunityTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TemplateKind kind;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 64)
    private String category;

    /**
     * Tags stored as a JSON-array string in a single TEXT column rather than a Postgres {@code
     * text[]} - keeps the Hibernate {@code ddl-auto: validate} mapping trivially correct across
     * driver versions and avoids an array-type binding, at the cost of tag filtering happening in
     * the service layer ({@link CommunityTemplateService#gallery}) rather than in SQL. The gallery
     * is small-scale, so an in-memory tag filter is more than adequate - same posture {@code
     * AgentService#listAll} takes for its own in-memory access filter. Serialised/parsed via {@link
     * TemplateSnapshotService}'s ObjectMapper.
     */
    @Column(name = "tags", columnDefinition = "TEXT")
    private String tagsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TemplateStatus status;

    /**
     * The sanitised, allow-listed design snapshot - see the class javadoc and {@link
     * TemplateSnapshotService}.
     */
    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    private String configJson;

    /**
     * Which tenant this originated from - admin traceability only, NEVER public, NEVER in {@link
     * #configJson}.
     */
    @Column(name = "source_tenant_id", length = 255)
    private String sourceTenantId;

    @Column(name = "submitted_by", length = 255)
    private String submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_by", length = 255)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    /**
     * Readiness + AI review captured at submit time (JSON), shown to the reviewing admin. Null if
     * none.
     */
    @Column(name = "ai_review_json", columnDefinition = "TEXT")
    private String aiReviewJson;

    @Column(name = "ai_reviewed_at")
    private Instant aiReviewedAt;

    @Column(name = "import_count", nullable = false)
    private long importCount;

    /**
     * The marketplace "verified" badge - a platform-admin endorsement layered ON TOP of APPROVED.
     * Approval means "cleared moderation, safe to publish"; verified means staff exercised/reviewed
     * the template beyond the snapshot allow-list check. Additive only: it never changes the
     * APPROVED-only visibility rule. Set via {@link #markVerified}/{@link #markUnverified} (admin
     * actions), never via {@link #applyEdit} - an owner edit bumps {@link #version} but does not
     * strip a previously-awarded badge (the badge attests the DESIGN, which an owner edit does
     * change - so edit DOES clear it; see applyEdit).
     */
    @Column(nullable = false)
    private boolean verified;

    @Column(name = "verified_by", length = 255)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /**
     * Denormalised rating aggregates, maintained transactionally by {@code
     * CommunityTemplateService} on every rating write so the public gallery can display and ORDER
     * BY rating without joining {@code community_template_rating} on every page load.
     */
    @Column(name = "rating_count", nullable = false)
    private long ratingCount;

    @Column(name = "rating_avg", nullable = false)
    private double ratingAvg;

    /** Bumped every time the owner edits the snapshot's design (see {@link #applyEdit}). */
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityTemplate() {
        // JPA
    }

    public CommunityTemplate(
            TemplateKind kind,
            String title,
            String summary,
            String category,
            String tagsJson,
            String configJson,
            String sourceTenantId,
            String submittedBy) {
        this.kind = kind;
        this.title = title;
        this.summary = summary;
        this.category = category;
        this.tagsJson = tagsJson;
        this.configJson = configJson;
        this.sourceTenantId = sourceTenantId;
        this.submittedBy = submittedBy;
        this.status = TemplateStatus.DRAFT;
        this.importCount = 0L;
        this.version = 1;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Owner edit of a DRAFT/REJECTED template's presentation + design. Bumps {@link #version} and
     * touches {@link #updatedAt}. Does NOT change status - lifecycle moves go through {@link
     * #transitionTo} exclusively so the legal-transition graph is enforced in exactly one place.
     */
    public void applyEdit(
            String title, String summary, String category, String tagsJson, String configJson) {
        this.title = title;
        this.summary = summary;
        this.category = category;
        this.tagsJson = tagsJson;
        if (configJson != null) {
            this.configJson = configJson;
        }
        // An owner edit changes the design a verification attested to, so the
        // badge cannot silently survive it - an admin must re-verify.
        this.verified = false;
        this.verifiedBy = null;
        this.verifiedAt = null;
        this.version += 1;
        this.updatedAt = Instant.now();
    }

    /** Admin awards the verified badge (APPROVED templates only - enforced by the service). */
    public void markVerified(String admin) {
        this.verified = true;
        this.verifiedBy = admin;
        this.verifiedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Admin removes the badge (e.g. the underlying design drifted, or it was awarded in error). */
    public void markUnverified() {
        this.verified = false;
        this.verifiedBy = null;
        this.verifiedAt = null;
        this.updatedAt = Instant.now();
    }

    /**
     * Re-applies the denormalised aggregates after a rating insert/update/ delete. Called by the
     * service inside the same transaction as the rating write, so the gallery never reads a torn
     * (count, avg) pair.
     */
    public void applyRatingAggregate(long count, double avg) {
        this.ratingCount = count;
        this.ratingAvg = avg;
        this.updatedAt = Instant.now();
    }

    /**
     * The single lifecycle mutator. Callers must have already checked {@link
     * TemplateStatus#canTransitionTo} (the service does, throwing {@link
     * IllegalTemplateTransitionException} on an illegal move) - this method just applies the state
     * change and the per-transition bookkeeping.
     */
    public void transitionTo(TemplateStatus target, String actor, String reason) {
        Instant now = Instant.now();
        switch (target) {
            case PENDING -> {
                this.submittedBy = actor;
                this.submittedAt = now;
                // A resubmission clears the previous rejection context.
                this.rejectReason = null;
            }
            case APPROVED -> {
                this.reviewedBy = actor;
                this.reviewedAt = now;
                this.rejectReason = null;
            }
            case REJECTED -> {
                this.reviewedBy = actor;
                this.reviewedAt = now;
                this.rejectReason = reason;
            }
            case WITHDRAWN -> {
                this.reviewedBy = actor;
                this.reviewedAt = now;
                if (reason != null) {
                    this.rejectReason = reason;
                }
            }
            case DRAFT -> {
                // no transition targets DRAFT in the graph; here for exhaustiveness.
            }
        }
        this.status = target;
        this.updatedAt = now;
    }

    public void recordImport() {
        this.importCount += 1;
        this.updatedAt = Instant.now();
    }

    /**
     * Stores the submit-time review and merges platform-derived tags (tool:/mcp:/setup:) into the
     * listing.
     */
    public void recordReview(String aiReviewJson, String mergedTagsJson) {
        this.aiReviewJson = aiReviewJson;
        this.aiReviewedAt = Instant.now();
        if (mergedTagsJson != null) {
            this.tagsJson = mergedTagsJson;
        }
        this.updatedAt = this.aiReviewedAt;
    }

    public String getAiReviewJson() {
        return aiReviewJson;
    }

    public Instant getAiReviewedAt() {
        return aiReviewedAt;
    }

    public UUID getId() {
        return id;
    }

    public TemplateKind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getCategory() {
        return category;
    }

    public String getTagsJson() {
        return tagsJson;
    }

    public TemplateStatus getStatus() {
        return status;
    }

    public String getConfigJson() {
        return configJson;
    }

    public String getSourceTenantId() {
        return sourceTenantId;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public long getImportCount() {
        return importCount;
    }

    public boolean isVerified() {
        return verified;
    }

    public String getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public long getRatingCount() {
        return ratingCount;
    }

    public double getRatingAvg() {
        return ratingAvg;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
