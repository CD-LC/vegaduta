package com.agenticai.core.sarvam.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

/**
 * Inbound request to activate a tenant's Sarvam AI API key. The compact
 * constructor trims {@code apiKey} before it reaches {@code @NotBlank} or
 * the encryptor - same "copy-paste trailing newline" defense as {@code
 * ProviderActivationRequest} applies for every LLM provider key in this
 * codebase; a key with surrounding whitespace still passes {@code
 * @NotBlank} but silently changes the bytes actually sent upstream.
 */
public record SarvamCredentialActivationRequest(
    @NotBlank(message = "apiKey is required") @Size(max = 4096) String apiKey
) {
    public SarvamCredentialActivationRequest {
        if (apiKey != null) {
            apiKey = apiKey.trim();
        }
    }
}
