package com.agenticai.core.platform;

import jakarta.validation.constraints.Size;
import com.agenticai.core.providers.ProviderName;

/**
 * Request body for {@code PUT /api/platform/llm-tasks/{task}}. {@code
 * apiKey} is optional/nullable - see {@link PlatformLlmTaskService#upsert}
 * for why a blank/absent value leaves any already-stored key untouched.
 */
public record PlatformLlmTaskUpsertRequest(ProviderName providerName, String model, @Size(max = 4096) String apiKey) {
    /**
     * Trims {@code model}/{@code apiKey} (blank collapsing to {@code null})
     * before either can be persisted or used - same compact-constructor posture,
     * and the same production cause, as {@code
     * com.agenticai.core.providers.PlatformHostedLlmProviderUpsertRequest}'s: a
     * task row was found holding {@code " gemini-2.0-flash-lite"}, one leading
     * space picked up on paste, which is exactly the shape of value an upstream
     * provider rejects with a {@code 404 NOT_FOUND} the admin form gives no way
     * to see. Collapsing blank to {@code null} is what lets {@code
     * PlatformLlmTaskService#upsert} answer with a 400 instead of letting the
     * {@code NOT NULL} constraint on {@code platform_llm_task_config.model}
     * surface as a 500.
     */
    public PlatformLlmTaskUpsertRequest {
        model = trimToNull(model);
        apiKey = trimToNull(apiKey);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
