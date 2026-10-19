package com.agenticai.core.omnichannel.email.dto;

import com.agenticai.core.omnichannel.email.EmailCredential;
import com.agenticai.core.omnichannel.email.EmailCredentialStatus;
import java.time.Instant;
import java.util.UUID;

public record EmailCredentialResponse(
    UUID id,
    String imapHost,
    int imapPort,
    String imapUsername,
    boolean useSsl,
    EmailCredentialStatus status,
    Instant createdAt,
    Instant lastValidatedAt,
    Instant lastPolledAt
) {
    public static EmailCredentialResponse from(EmailCredential credential) {
        return new EmailCredentialResponse(
            credential.getId(),
            credential.getImapHost(),
            credential.getImapPort(),
            credential.getImapUsername(),
            credential.isUseSsl(),
            credential.getStatus(),
            credential.getCreatedAt(),
            credential.getLastValidatedAt(),
            credential.getLastPolledAt()
        );
    }
}
