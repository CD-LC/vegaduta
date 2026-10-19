package com.agenticai.core.sarvam.dto;

import com.agenticai.core.sarvam.SarvamCredential;
import com.agenticai.core.sarvam.SarvamCredentialStatus;
import java.time.Instant;
import java.util.UUID;

/** Never carries the API key itself, encrypted or otherwise - only whether one is configured and its status. */
public record SarvamCredentialResponse(
    UUID id,
    SarvamCredentialStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    public static SarvamCredentialResponse from(SarvamCredential credential) {
        return new SarvamCredentialResponse(
            credential.getId(),
            credential.getStatus(),
            credential.getCreatedAt(),
            credential.getUpdatedAt()
        );
    }
}
