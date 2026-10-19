package com.agenticai.core.agents.decision.dto;

import com.agenticai.core.providers.ProviderStatus;
import com.agenticai.core.providers.capability.ProviderCredential;
import java.time.Instant;
import java.util.UUID;

/**
 * Never carries the API key itself, encrypted or otherwise - only whether one
 * is configured and its status. The compat-shim view of the tenant's {@code
 * provider_credential} row for {@code provider_key=TYPESAFE}; {@link
 * ProviderStatus} is the registry's enum, which covers the same ACTIVE/
 * INACTIVE pair the retired {@code JevCredentialStatus} used.
 */
public record JevCredentialResponse(
    UUID id,
    ProviderStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    public static JevCredentialResponse from(ProviderCredential credential) {
        return new JevCredentialResponse(
            credential.getId(),
            credential.getStatus(),
            credential.getCreatedAt(),
            credential.getUpdatedAt()
        );
    }
}
