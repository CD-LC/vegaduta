package com.agenticai.core.providers.capability;

import com.agenticai.core.providers.ProviderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The never-carries-the-secret view of a {@link ProviderCredential} - the
 * generic-registry counterpart of {@code JevCredentialResponse}/
 * {@code ProviderResponse}. {@code capabilities} is echoed so the console
 * can render "this key powers X" straight off the row.
 */
public record ProviderCredentialResponse(
    UUID id,
    String providerKey,
    List<String> capabilities,
    ProviderStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    public static ProviderCredentialResponse from(ProviderCredential credential) {
        return new ProviderCredentialResponse(
            credential.getId(),
            credential.getProviderKey(),
            credential.getCapabilities(),
            credential.getStatus(),
            credential.getCreatedAt(),
            credential.getUpdatedAt()
        );
    }
}
