package com.agenticai.core.providers.dto;

import jakarta.validation.constraints.Size;
import com.agenticai.core.providers.ProviderName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Inbound request to activate a provider. {@code apiKey} is used once to
 * validate + encrypt, then discarded — it is never echoed back or logged
 * (see logging config / no toString() override that includes it).
 *
 * <p>The compact constructor trims {@code apiKey} before it ever reaches
 * {@code @NotBlank}, a validator, or the encryptor. Direct feedback: "key is
 * proper, just now copied - check you might be sending some wrong structure"
 * on a real xAI "Incorrect API key provided" (HTTP 400) rejection - nothing
 * upstream of this DTO ever trimmed the value, so a stray leading/trailing
 * space or newline from a copy-paste (common: many provider consoles' "copy
 * key" button includes a trailing newline) silently changes the actual
 * bytes sent in the Authorization header versus what's visible on screen.
 * {@code @NotBlank} doesn't catch this - a key with surrounding whitespace
 * still reads as non-blank. Applies to every provider (Anthropic,
 * OpenRouter, Grok, Gemini, Meta, ...), since they all share this one DTO.
 */
public record ProviderActivationRequest(
    @NotNull(message = "providerName is required") ProviderName providerName,
    @NotBlank(message = "apiKey is required") @Size(max = 4096) String apiKey
) {
    public ProviderActivationRequest {
        if (apiKey != null) {
            apiKey = apiKey.trim();
        }
    }
}
