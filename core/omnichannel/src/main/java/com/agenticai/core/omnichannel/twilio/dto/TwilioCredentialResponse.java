package com.agenticai.core.omnichannel.twilio.dto;

import com.agenticai.core.omnichannel.twilio.TwilioCredential;
import com.agenticai.core.omnichannel.twilio.TwilioCredentialStatus;
import java.time.Instant;
import java.util.UUID;

/** {@code apiKeySid} is not a secret (Twilio API Key SIDs, like Account SIDs, are identifiers, not credentials) so it's safe to return; the API Key Secret itself never is. */
public record TwilioCredentialResponse(
    UUID id,
    String accountSid,
    String apiKeySid,
    boolean usesApiKey,
    String whatsAppFromNumber,
    TwilioCredentialStatus status,
    Instant createdAt,
    Instant lastValidatedAt
) {
    public static TwilioCredentialResponse from(TwilioCredential credential) {
        return new TwilioCredentialResponse(
            credential.getId(),
            credential.getAccountSid(),
            credential.getApiKeySid(),
            credential.usesApiKey(),
            credential.getWhatsAppFromNumber(),
            credential.getStatus(),
            credential.getCreatedAt(),
            credential.getLastValidatedAt()
        );
    }
}
