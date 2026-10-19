package com.agenticai.core.tenancy.apikey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link TenantApiKey} - tenant-schema table; the caller's TenantContext scopes it. */
public interface TenantApiKeyRepository extends JpaRepository<TenantApiKey, UUID> {

    Optional<TenantApiKey> findByApiKeyHash(String apiKeyHash);

    List<TenantApiKey> findAllByOrderByCreatedAtDesc();
}
