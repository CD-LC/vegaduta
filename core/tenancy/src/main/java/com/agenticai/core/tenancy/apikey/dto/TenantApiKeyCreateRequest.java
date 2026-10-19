package com.agenticai.core.tenancy.apikey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;

/**
 * POST /api/tenant/api-keys. {@code permissions} are catalog permission names
 * ({@code Permission} values, no {@code PERM_} prefix) - validated as a
 * subset of the issuer's own effective permissions. {@code expiresAt} is
 * optional; null = never expires.
 */
public record TenantApiKeyCreateRequest(
    @NotBlank @Size(max = 128) String name,
    Set<@Size(max = 64) String> permissions,
    Instant expiresAt
) {
}
