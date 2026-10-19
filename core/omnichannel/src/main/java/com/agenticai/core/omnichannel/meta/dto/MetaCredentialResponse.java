package com.agenticai.core.omnichannel.meta.dto;

import com.agenticai.core.omnichannel.meta.MetaWhatsAppCredential;
import com.agenticai.core.omnichannel.meta.MetaWhatsAppCredentialStatus;
import java.time.Instant;
import java.util.UUID;

public record MetaCredentialResponse(
    UUID id,
    String phoneNumberId,
    String wabaId,
    String displayPhoneNumber,
    MetaWhatsAppCredentialStatus status,
    Instant createdAt,
    Instant lastValidatedAt
) {
    public static MetaCredentialResponse from(MetaWhatsAppCredential credential) {
        return new MetaCredentialResponse(
            credential.getId(),
            credential.getPhoneNumberId(),
            credential.getWabaId(),
            credential.getDisplayPhoneNumber(),
            credential.getStatus(),
            credential.getCreatedAt(),
            credential.getLastValidatedAt()
        );
    }
}
