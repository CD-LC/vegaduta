package com.agenticai.core.tenancy.apikey.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Metadata view of a tenant API key - NEVER carries the key itself (it is
 * hash-only at rest; the plaintext exists only in the create/rotate
 * response).
 */
public record TenantApiKeyResponse(
    UUID id,
    String name,
    String keyPrefix,
    Set<String> permissions,
    String createdBy,
    Instant expiresAt,
    Instant lastUsedAt,
    boolean enabled,
    boolean expired,
    Instant createdAt,
    Instant updatedAt
) {
}
