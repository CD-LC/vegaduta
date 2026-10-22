package com.agenticai.core.providers;

import com.agenticai.core.json.JsonMappers;
import java.util.Base64;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code projectId} + {@code location} + a GCP service-account key (JSON, base64-encoded here) as
 * one unit. Vertex needs all three to authenticate, but {@link
 * LlmProviderCredential#getEncryptedApiKey()} is a single TEXT column shaped for a plain string API
 * key - rather than a schema change, this record serializes to/from exactly that one string
 * (encrypted as a whole, like any other provider's key), the same "reuse the existing column, don't
 * add a table" call {@code OPENAI} image generation already made for a different reason (see {@code
 * imagegen/pom.xml}'s description).
 */
public record VertexCredential(String projectId, String location, String serviceAccountKeyJson) {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    public String encode() {
        try {
            byte[] keyBytes =
                    serviceAccountKeyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return MAPPER.writeValueAsString(
                    new Wire(projectId, location, Base64.getEncoder().encodeToString(keyBytes)));
        } catch (Exception e) {
            throw new VertexCredentialException("Could not encode Vertex AI credential", e);
        }
    }

    public static VertexCredential decode(String raw) {
        try {
            Wire wire = MAPPER.readValue(raw, Wire.class);
            byte[] keyBytes = Base64.getDecoder().decode(wire.serviceAccountKeyBase64);
            return new VertexCredential(
                    wire.projectId,
                    wire.location,
                    new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new VertexCredentialException(
                    "Stored value is not a valid Vertex AI credential (expected project/location/key JSON)",
                    e);
        }
    }

    /**
     * Wire shape only - the base64 key bytes never appear as a nested JSON object, keeping the
     * encoded string a plain flat blob.
     */
    private record Wire(String projectId, String location, String serviceAccountKeyBase64) {}
}
