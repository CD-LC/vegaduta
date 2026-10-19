package com.agenticai.core.platform;

import com.agenticai.core.providers.ProviderName;
import java.time.Instant;

/**
 * Outbound view of one {@link PlatformTask}'s routing config. Deliberately
 * has no field for the API key, encrypted or otherwise - mirrors {@code
 * com.agenticai.core.providers.dto.ProviderResponse}'s "there is no way to
 * leak it through this type" posture. {@code hasCredential} is the boolean
 * a caller uses to know whether a key is already stored, without ever
 * seeing it.
 *
 * <p>{@code configured} is {@code false} for a {@link PlatformTask} that has
 * no {@link PlatformLlmTaskConfig} row yet - {@link PlatformLlmTaskService#list()}
 * still returns every {@link PlatformTask} value in that case (never a 404),
 * just with {@code providerName}/{@code model} null and {@code
 * hasCredential} false.
 */
public record PlatformLlmTaskConfigResponse(
    PlatformTask task,
    ProviderName providerName,
    String model,
    boolean hasCredential,
    Instant lastValidatedAt,
    boolean configured
) {

    public static PlatformLlmTaskConfigResponse unconfigured(PlatformTask task) {
        return new PlatformLlmTaskConfigResponse(task, null, null, false, null, false);
    }

    public static PlatformLlmTaskConfigResponse from(PlatformLlmTaskConfig entity) {
        return new PlatformLlmTaskConfigResponse(
            entity.getTask(),
            entity.getProviderName(),
            entity.getModel(),
            entity.getEncryptedApiKey() != null,
            entity.getLastValidatedAt(),
            true
        );
    }
}
