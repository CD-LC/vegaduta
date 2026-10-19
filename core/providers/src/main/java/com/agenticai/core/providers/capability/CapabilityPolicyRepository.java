package com.agenticai.core.providers.capability;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * One row per capability per tenant schema - {@code findByCapability} is the
 * only lookup any caller needs since the tenant schema itself is the scoping,
 * same convention {@link ProviderCredentialRepository#findByProviderKey}
 * uses.
 */
@Repository
public interface CapabilityPolicyRepository extends JpaRepository<CapabilityPolicy, UUID> {

    Optional<CapabilityPolicy> findByCapability(String capability);
}
