package com.agenticai.core.templates;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One member's rating of a marketplace template: a 1-5 star score plus an
 * optional free-text review. Maps to
 * {@code control_plane.community_template_rating} (migration
 * {@code V251__community_template_marketplace.sql}).
 *
 * <p><b>One row per (template, rater)</b> - the database enforces it with
 * {@code uq_community_template_rating_rater}; re-rating is an UPSERT (the
 * member's score changes in place, it is never a second vote).
 *
 * <p>{@link #raterSubject} is the Keycloak subject needed for the upsert and
 * "my rating" lookups. Like {@link CommunityTemplate#getSubmittedBy()} it is
 * NEVER returned on the public ratings listing - marketplace reviews are
 * anonymous-by-design at the API level (no display-name infrastructure exists
 * yet, and leaking the subject UUID would let a review be correlated with a
 * tenant account).
 */
@Entity
@Table(name = "community_template_rating", schema = "control_plane")
public class CommunityTemplateRating {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "rater_subject", nullable = false, length = 255)
    private String raterSubject;

    @Column(name = "rater_tenant_id", length = 255)
    private String raterTenantId;

    @Column(nullable = false)
    private int stars;

    @Column(columnDefinition = "TEXT")
    private String review;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CommunityTemplateRating() {
        // JPA
    }

    public CommunityTemplateRating(UUID templateId, String raterSubject, String raterTenantId, int stars, String review) {
        this.templateId = templateId;
        this.raterSubject = raterSubject;
        this.raterTenantId = raterTenantId;
        this.stars = stars;
        this.review = review;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Re-rating: updates stars + review in place (still one vote). */
    public void applyUpdate(int stars, String review) {
        this.stars = stars;
        this.review = review;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public String getRaterSubject() {
        return raterSubject;
    }

    public String getRaterTenantId() {
        return raterTenantId;
    }

    public int getStars() {
        return stars;
    }

    public String getReview() {
        return review;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
