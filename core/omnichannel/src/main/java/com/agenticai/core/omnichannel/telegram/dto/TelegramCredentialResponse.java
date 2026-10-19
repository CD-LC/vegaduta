package com.agenticai.core.omnichannel.telegram.dto;

import com.agenticai.core.omnichannel.telegram.TelegramCredential;
import com.agenticai.core.omnichannel.telegram.TelegramCredentialStatus;
import java.time.Instant;
import java.util.UUID;

public record TelegramCredentialResponse(
    UUID id,
    String botUserId,
    String botUsername,
    TelegramCredentialStatus status,
    Instant createdAt,
    Instant lastValidatedAt
) {
    public static TelegramCredentialResponse from(TelegramCredential credential) {
        return new TelegramCredentialResponse(
            credential.getId(),
            credential.getBotUserId(),
            credential.getBotUsername(),
            credential.getStatus(),
            credential.getCreatedAt(),
            credential.getLastValidatedAt()
        );
    }
}
