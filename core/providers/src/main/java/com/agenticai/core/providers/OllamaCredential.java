package com.agenticai.core.providers;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Ollama's BYOK credential needs more than a plain API key - a tenant-hosted
 * Ollama server's base URL, and an optional API key (Ollama itself has no
 * auth by default; the key exists purely for a tenant that has put a
 * reverse-proxy/gateway with its own auth in front of their server).
 *
 * <p>Same "encode a small record to one JSON string, store it in the
 * existing {@code encrypted_api_key} TEXT column" pattern this module uses
 * for every multi-field provider credential (see {@code
 * ProviderActivationRequest}'s javadoc for why the DTO layer needs zero
 * changes to carry this - {@code apiKey} is just a JSON string here instead
 * of a raw key). The frontend does the mirror-image JSON encoding in {@code
 * ProvidersPanel.tsx} and calls the exact same {@code POST /api/providers}
 * endpoint every other provider uses.
 */
public record OllamaCredential(String baseUrl, String apiKey) {

    private static final ObjectMapper MAPPER = JsonMappers.nonNull();

    public String encode() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode Ollama credential", e);
        }
    }

    /**
     * @throws IllegalStateException if {@code json} isn't a well-formed
     *         encoding of this record - callers decide how to surface that
     *         (a {@link com.agenticai.core.providers.validation.ProviderValidator}
     *         wraps this in a clear, user-facing {@code ProviderValidationException};
     *         a chat-client factory decoding an already-once-validated stored
     *         credential lets it propagate, since a failure there means the
     *         stored ciphertext itself is corrupt, a genuine 500).
     */
    public static OllamaCredential decode(String json) {
        try {
            return MAPPER.readValue(json, OllamaCredential.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed Ollama credential JSON", e);
        }
    }
}
