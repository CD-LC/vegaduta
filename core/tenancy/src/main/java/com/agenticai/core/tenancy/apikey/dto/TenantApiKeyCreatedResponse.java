package com.agenticai.core.tenancy.apikey.dto;

/**
 * The one response that carries the plaintext key - create and rotate only.
 * The client must store it immediately; it is unrecoverable afterwards
 * (hash-only at rest).
 */
public record TenantApiKeyCreatedResponse(TenantApiKeyResponse key, String apiKey) {
}
