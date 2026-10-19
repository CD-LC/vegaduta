package com.agenticai.core.knowledge.ocr.dto;

import jakarta.validation.constraints.Size;
import com.agenticai.core.knowledge.ocr.OcrProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Inbound request to activate a tenant's cloud OCR provider + BYOK API key.
 * Same trim-before-validate defense as {@code SarvamCredentialActivationRequest}
 * and every LLM provider key in this codebase.
 */
public record OcrProviderActivationRequest(
    @NotNull(message = "provider is required") OcrProvider provider,
    @NotBlank(message = "apiKey is required") @Size(max = 4096) String apiKey
) {
    public OcrProviderActivationRequest {
        if (apiKey != null) {
            apiKey = apiKey.trim();
        }
    }
}
