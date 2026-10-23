package com.agenticai.core.admin.permission;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPermissionGrantRepository extends JpaRepository<UserPermissionGrant, UUID> {

    List<UserPermissionGrant> findByTenantIdAndKeycloakUserId(
            String tenantId, String keycloakUserId);

    List<UserPermissionGrant> findByTenantIdAndPermission(String tenantId, String permission);

    void deleteByTenantIdAndKeycloakUserIdAndPermissionIn(
            String tenantId, String keycloakUserId, Collection<String> permissions);

    /**
     * Inserts one grant unless that user already holds the permission ({@code
     * uq_user_permission_grant} is {@code (keycloak_user_id, permission)}): a concurrent writer
     * that got there first makes this a no-op instead of a unique violation. A JPA {@code save}
     * cannot do that - the insert is deferred to flush, so the violation surfaces at commit, after
     * the caller's own try/catch has returned. Where the other writer's transaction has not
     * committed yet, Postgres makes this statement wait for it.
     *
     * @return 1 when a row was inserted, 0 when the user already held the permission
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
        INSERT INTO control_plane.user_permission_grant
            (id, tenant_id, keycloak_user_id, permission, granted_by, granted_at)
        VALUES (:id, :tenantId, :keycloakUserId, :permission, :grantedBy, :grantedAt)
        ON CONFLICT (keycloak_user_id, permission) DO NOTHING
        """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("tenantId") String tenantId,
            @Param("keycloakUserId") String keycloakUserId,
            @Param("permission") String permission,
            @Param("grantedBy") String grantedBy,
            @Param("grantedAt") Instant grantedAt);
}
