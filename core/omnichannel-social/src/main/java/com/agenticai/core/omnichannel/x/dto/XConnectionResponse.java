package com.agenticai.core.omnichannel.x.dto;

import com.agenticai.core.omnichannel.x.XCredential;
import com.agenticai.core.omnichannel.x.XCredentialStatus;
import java.time.Instant;
import java.util.UUID;

public record XConnectionResponse(
        UUID id,
        String clientId,
        String xUsername,
        XCredentialStatus status,
        Instant createdAt,
        Instant lastPolledAt) {
    public static XConnectionResponse from(XCredential credential) {
        return new XConnectionResponse(
                credential.getId(),
                credential.getClientId(),
                credential.getXUsername(),
                credential.getStatus(),
                credential.getCreatedAt(),
                credential.getLastPolledAt());
    }
}
