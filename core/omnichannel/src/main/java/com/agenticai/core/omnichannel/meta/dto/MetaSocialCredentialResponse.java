package com.agenticai.core.omnichannel.meta.dto;

import com.agenticai.core.omnichannel.meta.MetaSocialCredential;
import com.agenticai.core.omnichannel.meta.MetaSocialCredentialStatus;
import java.time.Instant;
import java.util.UUID;

public record MetaSocialCredentialResponse(
    UUID id,
    String pageId,
    String pageName,
    boolean instagramConnected,
    String instagramUsername,
    MetaSocialCredentialStatus status,
    Instant createdAt,
    Instant lastValidatedAt
) {
    public static MetaSocialCredentialResponse from(MetaSocialCredential credential) {
        return new MetaSocialCredentialResponse(
            credential.getId(),
            credential.getPageId(),
            credential.getPageName(),
            credential.hasInstagram(),
            credential.getInstagramUsername(),
            credential.getStatus(),
            credential.getCreatedAt(),
            credential.getLastValidatedAt()
        );
    }
}
