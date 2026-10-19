package com.agenticai.core.providers.capability;

import com.agenticai.core.providers.ProviderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Read/write for the unified {@code provider_credential} table - first wired
 * provider is TypeSafe/Jev ({@code provider_key=TYPESAFE}, V352 backfill).
 * See {@link ProviderCredential}'s javadoc for the registry design.
 */
public interface ProviderCredentialRepository extends JpaRepository<ProviderCredential, UUID> {

    Optional<ProviderCredential> findByProviderKey(String providerKey);

    /**
     * The registry's core query once this table is actually read from
     * (docs/GENERIC-PROVIDER-CAPABILITY-REGISTRY-2026-08-09.md §3.2's
     * "give me the best-priority ready provider for capability X") - every
     * ACTIVE credential whose capabilities array contains the requested
     * value, using the GIN index from V217 for the array containment check.
     */
    @Query(
        value = "SELECT * FROM provider_credential WHERE status = :status AND capabilities @> ARRAY[:capability]",
        nativeQuery = true
    )
    List<ProviderCredential> findByStatusAndCapability(String status, String capability);

    boolean existsByProviderKeyAndStatus(String providerKey, ProviderStatus status);
}
