package com.agenticai.core.elevenlabs;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ElevenLabsCredentialRepository extends JpaRepository<ElevenLabsCredential, UUID> {

    // Single active row per tenant schema - same convention as
    // SarvamCredentialRepository/TwilioCredentialRepository's single-row lookup.
    Optional<ElevenLabsCredential> findFirstByOrderByCreatedAtAsc();
}
