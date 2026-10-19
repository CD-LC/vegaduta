package com.agenticai.core.sharing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceGrantRepository extends JpaRepository<ResourceGrant, UUID> {

    Optional<ResourceGrant> findByResourceTypeAndResourceIdAndGranteeUserIdAndRevokedAtIsNull(
        ResourceType resourceType, UUID resourceId, String granteeUserId
    );

    List<ResourceGrant> findByResourceTypeAndResourceIdAndRevokedAtIsNullOrderByGrantedAtDesc(
        ResourceType resourceType, UUID resourceId
    );

    /** Both resource types - "shared with me" needs a caller's grants across the whole module, not one resource. */
    List<ResourceGrant> findByGranteeUserIdAndRevokedAtIsNullOrderByGrantedAtDesc(String granteeUserId);

    /**
     * Scoped to the resource it's supposedly for, not a bare
     * {@code findById} - revoking by grant id alone would let a caller who
     * knows/guesses a grant id revoke a grant on a DIFFERENT resource than
     * the one in the URL. Deliberately not filtered on {@code revokedAtIsNull}
     * (unlike the finders above) - {@link ResourceAccessService#revoke} needs
     * to find an already-revoked grant too, to stay idempotent-safe rather
     * than 404ing on a double-revoke.
     */
    Optional<ResourceGrant> findByIdAndResourceTypeAndResourceId(UUID id, ResourceType resourceType, UUID resourceId);
}
