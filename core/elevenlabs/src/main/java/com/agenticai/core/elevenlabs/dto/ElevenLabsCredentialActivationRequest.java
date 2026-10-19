package com.agenticai.core.elevenlabs.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

/**
 * Inbound request to activate a tenant's ElevenLabs API key. The compact
 * constructor trims {@code apiKey} before it reaches {@code @NotBlank} or
 * the encryptor - same "copy-paste trailing newline" defense as {@code
 * SarvamCredentialActivationRequest}/{@code ProviderActivationRequest}.
 */
public record ElevenLabsCredentialActivationRequest(
    @NotBlank(message = "apiKey is required") @Size(max = 4096) String apiKey
) {
    public ElevenLabsCredentialActivationRequest {
        if (apiKey != null) {
            apiKey = apiKey.trim();
        }
    }
}
