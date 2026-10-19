package com.agenticai.core.sdlc.dto;

import com.agenticai.core.sdlc.E2bCredential;
import com.agenticai.core.sdlc.E2bCredentialStatus;
import java.time.Instant;
import java.util.UUID;

/** Never carries the API key itself, encrypted or otherwise - only whether one is configured and its status. */
public record E2bCredentialResponse(
    UUID id,
    E2bCredentialStatus status,
    Instant createdAt,
    Instant updatedAt,
    String codeExecutionTemplateId
) {
    public static E2bCredentialResponse from(E2bCredential credential) {
        return new E2bCredentialResponse(
            credential.getId(),
            credential.getStatus(),
            credential.getCreatedAt(),
            credential.getUpdatedAt(),
            credential.getCodeExecutionTemplateId()
        );
    }
}
