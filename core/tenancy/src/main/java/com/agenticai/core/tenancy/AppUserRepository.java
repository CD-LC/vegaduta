package com.agenticai.core.tenancy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByKeycloakUserId(String keycloakUserId);

    List<AppUser> findByTenantId(UUID tenantId);

    Optional<AppUser> findByTenantIdAndEmail(UUID tenantId, String email);

    /**
     * Tenant erasure: removes every control-plane user row of an erased tenant (email, phone,
     * avatar key are personal data). One bulk statement in its own transaction - {@code
     * TenantDataErasureService} is deliberately not {@code @Transactional} itself (see its
     * javadoc).
     */
    @Transactional
    @Modifying
    @Query("DELETE FROM AppUser u WHERE u.tenantId = :tenantId")
    int deleteAllByTenantId(@Param("tenantId") UUID tenantId);

    /** V475 - the platform users list's per-person last-seen column, in one query. */
    @Query(
            "SELECT u.keycloakUserId AS keycloakUserId, u.lastSeenAt AS lastSeenAt FROM AppUser u "
                    + "WHERE u.lastSeenAt IS NOT NULL")
    List<KeycloakUserLastSeen> findAllLastSeen();

    /**
     * V475 - the platform roster's per-tenant "last activity" half that lives in the control plane.
     */
    @Query(
            "SELECT u.tenantId AS tenantId, MAX(u.lastSeenAt) AS lastSeenAt FROM AppUser u "
                    + "WHERE u.lastSeenAt IS NOT NULL GROUP BY u.tenantId")
    List<TenantLastSeen> findLastSeenPerTenant();

    /**
     * V475 - one tenant's most recent sign-of-life (platform tenant detail); null if nobody has
     * been seen.
     */
    @Query("SELECT MAX(u.lastSeenAt) FROM AppUser u WHERE u.tenantId = :tenantId")
    Instant findLatestSeenForTenant(@Param("tenantId") UUID tenantId);

    interface KeycloakUserLastSeen {
        String getKeycloakUserId();

        Instant getLastSeenAt();
    }

    interface TenantLastSeen {
        UUID getTenantId();

        Instant getLastSeenAt();
    }
}
