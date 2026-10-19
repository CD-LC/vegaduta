package com.agenticai.core.tenancy.apikey.dto;

import jakarta.validation.constraints.NotNull;

/** PATCH /api/tenant/api-keys/{id} - enable/disable the key. */
public record TenantApiKeyToggleRequest(@NotNull Boolean enabled) {
}
