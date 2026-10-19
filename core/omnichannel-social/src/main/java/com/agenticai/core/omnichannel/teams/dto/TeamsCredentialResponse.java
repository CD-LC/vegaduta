package com.agenticai.core.omnichannel.teams.dto;

import com.agenticai.core.omnichannel.teams.TeamsCredential;
import com.agenticai.core.omnichannel.teams.TeamsCredentialStatus;
import java.time.Instant;
import java.util.UUID;

public record TeamsCredentialResponse(
        UUID id,
        String appId,
        String aadTenantId,
        TeamsCredentialStatus status,
        Instant createdAt,
        Instant lastValidatedAt) {
    public static TeamsCredentialResponse from(TeamsCredential credential) {
        return new TeamsCredentialResponse(
                credential.getId(),
                credential.getAppId(),
                credential.getAadTenantId(),
                credential.getStatus(),
                credential.getCreatedAt(),
                credential.getLastValidatedAt());
    }
}
