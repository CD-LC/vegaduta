package com.agenticai.core.secrets;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-schema repository for the vault. Every finder here is name- or
 * id-based; there is intentionally no "find all including values" style query,
 * because listing is a UI concern that must never carry ciphertext in bulk.
 */
public interface TenantSecretRepository extends JpaRepository<TenantSecret, UUID> {

    /**
     * The resolver's hot path: a name resolves to the caller's own USER-scoped
     * secret if they have one, otherwise to the TENANT-scoped secret of that
     * name.
     *
     * <p>Ordering is the entire point of this query - {@code scope} is a
     * string column and 'USER' sorts after 'TENANT' alphabetically, so an
     * explicit CASE is used rather than relying on that coincidence. Personal
     * secrets shadow shared ones, never the reverse; a user who has stored
     * their own credential must not silently act as the workspace account.
     */
    @Query("""
        SELECT s FROM TenantSecret s
        WHERE s.name = :name
          AND (s.scope = com.agenticai.core.secrets.SecretScope.TENANT
               OR (s.scope = com.agenticai.core.secrets.SecretScope.USER AND s.ownerUserId = :userId))
        ORDER BY CASE WHEN s.scope = com.agenticai.core.secrets.SecretScope.USER THEN 0 ELSE 1 END
        """)
    List<TenantSecret> findResolvableByName(@Param("name") String name, @Param("userId") String userId);

    Optional<TenantSecret> findByNameAndScopeAndOwnerUserId(String name, SecretScope scope, String ownerUserId);

    /**
     * The sandboxed-code path: a TENANT-scoped secret that has been explicitly
     * opted in to code-tool use.
     *
     * <p>Deliberately NOT a filter applied on top of
     * {@link #findResolvableByName}: USER scope must never reach this path at
     * all (tenant-authored code is run by many members, so resolving a personal
     * secret would let the author harvest it), and expressing that as a
     * separate query rather than an extra predicate makes it impossible to
     * widen by accident later.
     */
    @Query("""
        SELECT s FROM TenantSecret s
        WHERE s.name = :name
          AND s.scope = com.agenticai.core.secrets.SecretScope.TENANT
          AND s.codeToolAccess = true
        """)
    Optional<TenantSecret> findCodeToolReadable(@Param("name") String name);

    @Query("SELECT s FROM TenantSecret s WHERE s.name = :name AND s.scope = com.agenticai.core.secrets.SecretScope.TENANT")
    Optional<TenantSecret> findTenantScoped(@Param("name") String name);

    /**
     * Everything the calling user is allowed to SEE - all TENANT-scoped rows
     * plus their own USER-scoped rows. Another user's personal secrets are not
     * listed at all, not even as a masked placeholder.
     */
    @Query("""
        SELECT s FROM TenantSecret s
        WHERE s.scope = com.agenticai.core.secrets.SecretScope.TENANT
           OR (s.scope = com.agenticai.core.secrets.SecretScope.USER AND s.ownerUserId = :userId)
        ORDER BY s.name ASC
        """)
    List<TenantSecret> findVisibleTo(@Param("userId") String userId);
}
