package com.agenticai.core.providers.capability;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/capabilities/{capability}/credential} body. {@code
 * provider} is optional while a capability has exactly one registered
 * provider (DECISION -> TYPESAFE); once a capability gains a second
 * candidate it becomes required, and the controller says so in the 400.
 */
public record CapabilityCredentialRequest(
    @NotBlank(message = "apiKey is required")
    @Size(max = 4096, message = "apiKey is implausibly long")
    String apiKey,

    /** The {@code provider_key} to activate - e.g. {@code "TYPESAFE"}. Null picks the capability's sole provider. */
    @Size(max = 32, message = "provider is implausibly long")
    String provider
) {
}
