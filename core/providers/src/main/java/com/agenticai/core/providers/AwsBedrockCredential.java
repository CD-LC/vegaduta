package com.agenticai.core.providers;

import com.agenticai.core.json.JsonMappers;
import tools.jackson.databind.ObjectMapper;

/**
 * AWS Bedrock's BYOK credential needs three fields, not a plain key: an IAM {@code
 * accessKeyId}/{@code secretAccessKey} pair (Bedrock uses AWS SigV4 request signing, not a
 * bearer/API-key header at all) and the {@code region} the tenant's Bedrock access is provisioned
 * in (Bedrock is region-scoped - a key valid in one region can 403 in another). Deliberately does
 * NOT carry a model id: an agent's existing free-text {@code model} field already carries Bedrock's
 * full model id (e.g. {@code "anthropic.claude-3-5-sonnet-20241022-v2:0"}) the same way it does for
 * every other provider - see {@link AwsBedrockCredential}'s callers ({@code
 * AwsBedrockAgentChatClientFactory}) for how {@code create(apiKey, model, ...)}'s existing {@code
 * model} parameter is reused instead of duplicating model-id storage here.
 *
 * <p>Same "encode a small record to one JSON string, store it in the existing {@code
 * encrypted_api_key} TEXT column" pattern as {@link OllamaCredential} - see that class's javadoc
 * for the shape this mirrors.
 */
public record AwsBedrockCredential(String accessKeyId, String secretAccessKey, String region) {

    private static final ObjectMapper MAPPER = JsonMappers.nonNull();

    public String encode() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode AWS Bedrock credential", e);
        }
    }

    /**
     * @see OllamaCredential#decode(String) for the failure-handling contract this mirrors.
     */
    public static AwsBedrockCredential decode(String json) {
        try {
            return MAPPER.readValue(json, AwsBedrockCredential.class);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed AWS Bedrock credential JSON", e);
        }
    }
}
