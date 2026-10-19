package com.agenticai.core.omnichannel.meta;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetaWhatsAppCredentialRepository extends JpaRepository<MetaWhatsAppCredential, UUID> {

    // Same "at most one row per tenant schema" convention as TwilioCredentialRepository.
    Optional<MetaWhatsAppCredential> findFirstByOrderByCreatedAtAsc();
}
