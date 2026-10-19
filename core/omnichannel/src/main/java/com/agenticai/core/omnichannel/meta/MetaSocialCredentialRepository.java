package com.agenticai.core.omnichannel.meta;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MetaSocialCredentialRepository extends JpaRepository<MetaSocialCredential, UUID> {

    // Same "at most one row per tenant schema" convention as
    // MetaWhatsAppCredentialRepository/TwilioCredentialRepository - a tenant
    // connects one Facebook Page, which is what serves both Messenger and
    // (if linked) Instagram.
    Optional<MetaSocialCredential> findFirstByOrderByCreatedAtAsc();
}
