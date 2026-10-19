package com.agenticai.core.sdlc;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface E2bCredentialRepository extends JpaRepository<E2bCredential, UUID> {

    // Single active row per tenant schema - same convention as
    // SarvamCredentialRepository/TwilioCredentialRepository's single-row lookup.
    Optional<E2bCredential> findFirstByOrderByCreatedAtAsc();
}
