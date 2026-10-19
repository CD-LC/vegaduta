package com.agenticai.core.providers;

import java.time.Instant;

/** GET/PUT {@code /api/platform/hosted-llm-providers/settings} wire shape - see {@link HostedLlmPoolSettings}. */
public record HostedLlmPoolSettingsResponse(boolean enabled, Instant updatedAt, String updatedBy) {

    public static HostedLlmPoolSettingsResponse from(HostedLlmPoolSettings settings) {
        return new HostedLlmPoolSettingsResponse(settings.isEnabled(), settings.getUpdatedAt(), settings.getUpdatedBy());
    }
}
