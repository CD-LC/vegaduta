package com.agenticai.core.admin.sso;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantSsoConfigRepository extends JpaRepository<TenantSsoConfig, UUID> {

    Optional<TenantSsoConfig> findByTenantSchema(String tenantSchema);
}
