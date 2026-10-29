package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The multi-field BYOK credential records - each packs several fields into the
 * one {@code encrypted_api_key} TEXT column as a JSON (or base64-keyed JSON)
 * blob, so encode/decode must round-trip exactly and a malformed blob must fail
 * with the documented exception a {@code ProviderValidator} can translate into
 * a 400.
 */
class ProviderCredentialRecordsTest {

    @Test
    void ollamaCredentialRoundTripsBothFields() {
        OllamaCredential credential = new OllamaCredential("http://ollama.internal:11434", "proxy-key");
        OllamaCredential decoded = OllamaCredential.decode(credential.encode());
        assertEquals("http://ollama.internal:11434", decoded.baseUrl());
        assertEquals("proxy-key", decoded.apiKey());
    }

    @Test
    void ollamaCredentialWithoutAKeyStaysNull() {
        OllamaCredential decoded = OllamaCredential.decode(new OllamaCredential("http://h:11434", null).encode());
        assertEquals("http://h:11434", decoded.baseUrl());
        assertNull(decoded.apiKey());
    }

    @Test
    void ollamaCredentialRejectsMalformedJson() {
        assertThrows(IllegalStateException.class, () -> OllamaCredential.decode("not-json"));
    }

    @Test
    void azureCredentialRoundTripsAndNormalizes() {
        AzureOpenAiCredential credential =
            new AzureOpenAiCredential("https://res.openai.azure.com/", "key", "gpt-4o-deploy", "2024-06-01");
        // The compact constructor trims a trailing slash and keeps an explicit api-version.
        assertEquals("https://res.openai.azure.com", credential.endpoint());
        assertEquals("2024-06-01", credential.apiVersion());
        AzureOpenAiCredential decoded = AzureOpenAiCredential.decode(credential.encode());
        assertEquals("https://res.openai.azure.com", decoded.endpoint());
        assertEquals("key", decoded.apiKey());
        assertEquals("gpt-4o-deploy", decoded.deploymentName());
        assertEquals("2024-06-01", decoded.apiVersion());
    }

    @Test
    void azureCredentialDefaultsTheApiVersionWhenBlank() {
        AzureOpenAiCredential blank = new AzureOpenAiCredential("https://e", "k", "d", " ");
        AzureOpenAiCredential missing = new AzureOpenAiCredential("https://e", "k", "d", null);
        assertEquals(AzureOpenAiCredential.DEFAULT_API_VERSION, blank.apiVersion());
        assertEquals(AzureOpenAiCredential.DEFAULT_API_VERSION, missing.apiVersion());
        assertEquals("2024-10-21", AzureOpenAiCredential.DEFAULT_API_VERSION);
    }

    @Test
    void azureCredentialRejectsMalformedJson() {
        assertThrows(IllegalStateException.class, () -> AzureOpenAiCredential.decode("{oops"));
    }

    @Test
    void bedrockCredentialRoundTripsAllThreeFields() {
        AwsBedrockCredential credential = new AwsBedrockCredential("AKIAEXAMPLE", "secret", "us-east-1");
        AwsBedrockCredential decoded = AwsBedrockCredential.decode(credential.encode());
        assertEquals("AKIAEXAMPLE", decoded.accessKeyId());
        assertEquals("secret", decoded.secretAccessKey());
        assertEquals("us-east-1", decoded.region());
    }

    @Test
    void bedrockCredentialRejectsMalformedJson() {
        assertThrows(IllegalStateException.class, () -> AwsBedrockCredential.decode("[1,2"));
    }

    @Test
    void vertexCredentialRoundTripsThroughBase64KeyMaterial() {
        String serviceAccount = "{\"type\":\"service_account\",\"project_id\":\"p-1\",\"private_key\":\"-----KEY-----\"}";
        VertexCredential credential = new VertexCredential("p-1", "us-central1", serviceAccount);
        String encoded = credential.encode();
        assertTrue(encoded.contains("p-1"), encoded);
        VertexCredential decoded = VertexCredential.decode(encoded);
        assertEquals("p-1", decoded.projectId());
        assertEquals("us-central1", decoded.location());
        assertEquals(serviceAccount, decoded.serviceAccountKeyJson());
    }

    @Test
    void vertexCredentialRejectsMalformedAndBadBase64Blobs() {
        assertThrows(VertexCredentialException.class, () -> VertexCredential.decode("nope"));
        // Valid JSON, but the key field is not base64.
        assertThrows(
            VertexCredentialException.class,
            () -> VertexCredential.decode(
                "{\"projectId\":\"p\",\"location\":\"l\",\"serviceAccountKeyBase64\":\"!!!not-base64!!!\"}"));
    }
}
