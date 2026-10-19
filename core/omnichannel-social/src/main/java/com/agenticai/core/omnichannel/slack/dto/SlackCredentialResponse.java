package com.agenticai.core.omnichannel.slack.dto;

import com.agenticai.core.omnichannel.slack.SlackCredential;
import com.agenticai.core.omnichannel.slack.SlackCredentialStatus;
import java.time.Instant;
import java.util.UUID;

public record SlackCredentialResponse(
        UUID id,
        String teamId,
        String teamName,
        SlackCredentialStatus status,
        Instant createdAt,
        Instant lastValidatedAt) {
    public static SlackCredentialResponse from(SlackCredential credential) {
        return new SlackCredentialResponse(
                credential.getId(),
                credential.getTeamId(),
                credential.getTeamName(),
                credential.getStatus(),
                credential.getCreatedAt(),
                credential.getLastValidatedAt());
    }
}
