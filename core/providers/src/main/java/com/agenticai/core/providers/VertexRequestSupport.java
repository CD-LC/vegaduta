package com.agenticai.core.providers;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.validation.ProviderValidationException;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Admin-input checks for the two Vertex AI hosted-provider auth types ({@code VERTEX_ADC}, {@code
 * VERTEX_SERVICE_ACCOUNT}), split out of {@link PlatformHostedLlmProviderService}, which calls them
 * from its create and update paths. Stateless.
 */
final class VertexRequestSupport {

    private VertexRequestSupport() {}

    /**
     * Validates + normalizes admin input for a {@code VERTEX_ADC}/{@code VERTEX_SERVICE_ACCOUNT}
     * row - shared by {@code createVertex}/{@code updateVertex}. {@code vertexProjectId} is
     * required for both Vertex types (there is no such thing as a Vertex call with no GCP project),
     * {@code model} is required same as every other row (e.g. {@code "gemini-2.5-flash"}), and a
     * blank {@code vertexLocation} defaults to {@link
     * PlatformHostedLlmAuthType#DEFAULT_VERTEX_LOCATION}.
     *
     * <p>{@code baseUrl} is informational only - {@code HostedLlmChatClientFactory}'s Vertex branch
     * drives the SDK from {@code project}/{@code location}/credentials, never from this row's
     * {@code base_url} column - but that column is {@code NOT NULL}, so this derives the real
     * regional Vertex REST host ({@code https://{location}-aiplatform.googleapis.com}) rather than
     * storing an opaque placeholder.
     */
    static PlatformHostedLlmProviderService.ResolvedVertexRequest resolve(
            PlatformHostedLlmProviderUpsertRequest request) {
        if (!StringUtils.hasText(request.vertexProjectId())) {
            throw new ProviderValidationException(
                    "A GCP project id is required for a Vertex AI hosted provider.");
        }
        if (!StringUtils.hasText(request.model())) {
            throw new ProviderValidationException(
                    "A model id is required for a hosted provider (e.g. \"gemini-2.5-flash\").");
        }
        String location =
                StringUtils.hasText(request.vertexLocation())
                        ? request.vertexLocation()
                        : PlatformHostedLlmAuthType.DEFAULT_VERTEX_LOCATION;
        String baseUrl = "https://" + location + "-aiplatform.googleapis.com";
        return new PlatformHostedLlmProviderService.ResolvedVertexRequest(
                baseUrl, request.model(), request.vertexProjectId(), location);
    }

    private static final ObjectMapper VERTEX_SERVICE_ACCOUNT_JSON_MAPPER = JsonMappers.plain();

    /**
     * Light validation only - confirms the pasted text parses as a JSON object before it's
     * encrypted and saved, so an obviously-wrong paste (truncated, wrong file, plain text) fails
     * fast with a clear message instead of surfacing as an opaque credential error on the first
     * real chat call. Does not check for the specific fields a GCP service-account key requires
     * ({@code type}, {@code project_id}, {@code private_key}, ...) - {@link
     * com.google.auth.oauth2.GoogleCredentials#fromStream} (see {@code
     * VertexAccessTokenProvider#loadFromJson}) is the real, authoritative parser for that shape and
     * runs at actual chat time.
     */
    static void validateServiceAccountJson(String rawJson) {
        try {
            JsonNode node = VERTEX_SERVICE_ACCOUNT_JSON_MAPPER.readTree(rawJson);
            if (node == null || !node.isObject()) {
                throw new ProviderValidationException(
                        "The pasted service-account key is not valid JSON (expected a GCP"
                                + " service-account key object).");
            }
        } catch (ProviderValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderValidationException(
                    "The pasted service-account key is not valid JSON (expected a GCP"
                            + " service-account key object).",
                    e);
        }
    }
}
