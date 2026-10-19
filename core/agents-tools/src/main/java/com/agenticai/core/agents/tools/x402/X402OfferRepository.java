package com.agenticai.core.agents.tools.x402;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface X402OfferRepository extends JpaRepository<X402Offer, UUID> {

    /** Public pay-per-call lookup - runs with no TenantContext (control_plane). */
    Optional<X402Offer> findBySlugAndStatus(String slug, X402Offer.Status status);

    /** Slug-existence check for publish (any status - a disabled slug stays taken). */
    Optional<X402Offer> findBySlug(String slug);

    List<X402Offer> findByAgentIdOrderByCreatedAtDesc(UUID agentId);

    List<X402Offer> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
