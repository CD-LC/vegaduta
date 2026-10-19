package com.agenticai.core.sarvam;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SarvamCredentialRepository extends JpaRepository<SarvamCredential, UUID> {

    // Single active row per tenant schema - same convention as
    // TwilioCredentialRepository/LlmProviderCredentialRepository's single-row lookup.
    Optional<SarvamCredential> findFirstByOrderByCreatedAtAsc();
}
