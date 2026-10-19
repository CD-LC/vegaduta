package com.agenticai.core.omnichannel.whatsapp.policy;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WhatsAppRecipientBackoffRepository
        extends JpaRepository<WhatsAppRecipientBackoff, String> {}
