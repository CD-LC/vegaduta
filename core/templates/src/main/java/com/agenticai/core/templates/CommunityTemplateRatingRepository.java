package com.agenticai.core.templates;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link CommunityTemplateRating}. Same control-plane posture
 * as {@link CommunityTemplateRepository}: not tenant-schema-scoped, so every
 * caller in {@code CommunityTemplateService} does its own visibility checks
 * (ratings are only ever written/read against APPROVED templates).
 */
public interface CommunityTemplateRatingRepository extends JpaRepository<CommunityTemplateRating, UUID> {

    /** The one-vote-per-member upsert key. */
    Optional<CommunityTemplateRating> findByTemplateIdAndRaterSubject(UUID templateId, String raterSubject);

    /** Public "recent reviews" listing for one template. */
    List<CommunityTemplateRating> findByTemplateIdOrderByCreatedAtDesc(UUID templateId, Pageable pageable);

    /** Aggregate recompute after a rating write - count. */
    long countByTemplateId(UUID templateId);

    /**
     * Aggregate recompute after a rating write - mean stars. Returns null when
     * the template has no ratings (avg over an empty set); the service maps
     * that to 0.
     */
    @Query("SELECT AVG(r.stars) FROM CommunityTemplateRating r WHERE r.templateId = :templateId")
    Double averageStars(@Param("templateId") UUID templateId);

    /** Member removes their own rating. */
    void deleteByTemplateIdAndRaterSubject(UUID templateId, String raterSubject);
}
