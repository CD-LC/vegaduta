package com.agenticai.core.providers;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Azure OpenAI's BYOK credential needs four fields, not a plain key: the
 * per-tenant resource {@code endpoint}, the {@code apiKey} itself, the
 * {@code deploymentName} (Azure addresses a model through a tenant-named
 * deployment, not a bare model id - the deployment is what actually pins the
 * underlying model), and an {@code apiVersion} (Azure's REST surface is
 * versioned via a required query parameter, not a URL path segment or
 * header).
 *
 * <p>Same "encode a small record to one JSON string, store it in the
 * existing {@code encrypted_api_key} TEXT column" pattern as {@link
 * OllamaCredential} - see that class's javadoc for the shape this mirrors.
 *
 * <p>{@code apiVersion} defaults to {@code "2024-10-21"} (a stable, GA Azure
 * OpenAI data-plane API version as of this platform's July 2026 build) when
 * left blank - callers that need a newer/older version can still supply one
 * explicitly via the "advanced" field in the activation form.
 */
public record AzureOpenAiCredential(String endpoint, String apiKey, String deploymentName, String apiVersion) {

    public static final String DEFAULT_API_VERSION = "2024-10-21";

    private static final ObjectMapper MAPPER = JsonMappers.nonNull();

    public AzureOpenAiCredential {
        if (apiVersion == null || apiVersion.isBlank()) {
            apiVersion = DEFAULT_API_VERSION;
        }
        if (endpoint != null) {
            // Trim a trailing slash so callers can build "endpoint + path"
            // without ever producing a doubled "//".
            endpoint = endpoint.replaceAll("/+$", "");
        }
    }

    public String encode() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode Azure OpenAI credential", e);
        }
    }

    /** @see OllamaCredential#decode(String) for the failure-handling contract this mirrors. */
    public static AzureOpenAiCredential decode(String json) {
        try {
            return MAPPER.readValue(json, AzureOpenAiCredential.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed Azure OpenAI credential JSON", e);
        }
    }
}
