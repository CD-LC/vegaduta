package com.agenticai.core.sdlc.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Inbound request to activate a tenant's E2B API key. The compact
 * constructor trims {@code apiKey} before it reaches {@code @NotBlank} or
 * the encryptor - same "copy-paste trailing newline" defense as {@code
 * SarvamCredentialActivationRequest}/{@code ProviderActivationRequest}; a key
 * with surrounding whitespace still passes {@code @NotBlank} but silently
 * changes the bytes actually sent upstream.
 */
public record E2bCredentialActivationRequest(
    @NotBlank(message = "apiKey is required") String apiKey
) {
    public E2bCredentialActivationRequest {
        if (apiKey != null) {
            apiKey = apiKey.trim();
        }
    }
}
