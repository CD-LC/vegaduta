package com.agenticai.core.omnichannel.twilio.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code apiKeySid}/{@code apiKeySecret} are optional and independent of
 * each other's presence being enforced here - {@code
 * TwilioCredentialService.activate()} rejects a request that sets one
 * without the other, since a partial API Key is unusable. {@code authToken}
 * stays required even when an API Key is also supplied - see {@code
 * TwilioCredential}'s javadoc for why the Auth Token can never be dropped
 * entirely (inbound webhook signature validation has no API-Key equivalent).
 */
public record TwilioActivationRequest(
    @NotBlank String accountSid,
    @NotBlank @Size(max = 4096) String authToken,
    String apiKeySid,
    @Size(max = 4096) String apiKeySecret,
    @NotBlank String whatsAppFromNumber
) {
    public boolean hasApiKey() {
        return apiKeySid != null && !apiKeySid.isBlank();
    }
}
