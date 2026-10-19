package com.agenticai.core.providers.dto;

import com.agenticai.core.providers.LlmProviderCredential;
import com.agenticai.core.providers.ProviderHealth;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound provider view. Deliberately has no field for the API key,
 * encrypted or otherwise — there is no way to leak it through this type.
 */
public record ProviderResponse(
    UUID id,
    ProviderName providerName,
    ProviderStatus status,
    Instant createdAt,
    Instant updatedAt,
    Instant lastValidatedAt,
    /**
     * What this workspace's own call history says about the key, for ACTIVE credentials only (null otherwise, and
     * null when no call-history source is wired): {@code status} ok | rejected | unknown, the 14-day failure and
     * success counts, the newest failure's summary, and until when background calls are paused. See
     * {@link ProviderHealth}.
     */
    ProviderHealth health
) {
    public ProviderResponse(UUID id, ProviderName providerName, ProviderStatus status, Instant createdAt,
                            Instant updatedAt, Instant lastValidatedAt) {
        this(id, providerName, status, createdAt, updatedAt, lastValidatedAt, null);
    }

    public ProviderResponse withHealth(ProviderHealth health) {
        return new ProviderResponse(id, providerName, status, createdAt, updatedAt, lastValidatedAt, health);
    }

    public static ProviderResponse from(LlmProviderCredential entity) {
        return new ProviderResponse(
            entity.getId(),
            entity.getProviderName(),
            entity.getStatus(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getLastValidatedAt()
        );
    }
}
