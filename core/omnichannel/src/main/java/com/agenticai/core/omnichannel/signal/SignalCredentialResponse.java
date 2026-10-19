package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import java.time.Instant;
import java.util.UUID;

/** Never carries the auth token, only whether one is set. */
public record SignalCredentialResponse(
    UUID id,
    String baseUrl,
    String number,
    boolean hasAuthToken,
    UnknownSenderPolicy unknownSenders,
    SignalGroupMode groupMode,
    SignalCredentialStatus status,
    Instant createdAt,
    Instant lastValidatedAt
) {
    public static SignalCredentialResponse from(SignalCredential credential) {
        return new SignalCredentialResponse(
            credential.getId(),
            credential.getBaseUrl(),
            credential.getAccountNumber(),
            credential.getEncryptedAuthToken() != null,
            credential.getUnknownSenders(),
            credential.getGroupMode(),
            credential.getStatus(),
            credential.getCreatedAt(),
            credential.getLastValidatedAt()
        );
    }
}
