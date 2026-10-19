package com.agenticai.core.admin.sso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantScimTokenRepository extends JpaRepository<TenantScimToken, UUID> {

    List<TenantScimToken> findByTenantSchemaAndRevokedAtIsNull(String tenantSchema);

    Optional<TenantScimToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    /** Touch on successful auth - single UPDATE, no entity round-trip. */
    @Modifying
    @Query("UPDATE TenantScimToken t SET t.lastUsedAt = CURRENT_TIMESTAMP WHERE t.id = :id")
    void markUsed(@Param("id") UUID id);
}
