package com.agenticai.core.omnichannel.twilio;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TwilioCredentialRepository extends JpaRepository<TwilioCredential, UUID> {

    // Single-tenant MVP: at most one row ever exists, same convention as
    // LlmProviderCredentialRepository.findByProviderName's single-row lookup.
    Optional<TwilioCredential> findFirstByOrderByCreatedAtAsc();
}
