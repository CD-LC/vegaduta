package com.agenticai.core.omnichannel.whatsappqr;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WhatsAppQrConnectionRepository extends JpaRepository<WhatsAppQrConnection, UUID> {

    /** One connection row per tenant schema - the same convention {@code SignalCredentialRepository} uses. */
    Optional<WhatsAppQrConnection> findFirstByOrderByCreatedAtAsc();

    Optional<WhatsAppQrConnection> findBySessionKey(String sessionKey);
}
