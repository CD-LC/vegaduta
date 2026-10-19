package com.agenticai.core.omnichannel.discord.dto;

import com.agenticai.core.omnichannel.discord.DiscordCredential;
import com.agenticai.core.omnichannel.discord.DiscordCredentialStatus;
import java.time.Instant;
import java.util.UUID;

public record DiscordCredentialResponse(
        UUID id,
        String applicationId,
        String botUsername,
        DiscordCredentialStatus status,
        Instant createdAt,
        Instant lastValidatedAt) {
    public static DiscordCredentialResponse from(DiscordCredential credential) {
        return new DiscordCredentialResponse(
                credential.getId(),
                credential.getApplicationId(),
                credential.getBotUsername(),
                credential.getStatus(),
                credential.getCreatedAt(),
                credential.getLastValidatedAt());
    }
}
