package com.agenticai.core.elevenlabs.dto;

import com.agenticai.core.elevenlabs.ElevenLabsCredential;
import com.agenticai.core.elevenlabs.ElevenLabsCredentialStatus;
import java.time.Instant;
import java.util.UUID;

/** Never carries the API key itself, encrypted or otherwise - only whether one is configured and its status. */
public record ElevenLabsCredentialResponse(
    UUID id,
    ElevenLabsCredentialStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    public static ElevenLabsCredentialResponse from(ElevenLabsCredential credential) {
        return new ElevenLabsCredentialResponse(
            credential.getId(),
            credential.getStatus(),
            credential.getCreatedAt(),
            credential.getUpdatedAt()
        );
    }
}
