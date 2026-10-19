package com.agenticai.core.providers;

/** PUT {@code /api/platform/hosted-llm-providers/settings} request body - see {@link HostedLlmPoolSettings}. */
public record HostedLlmPoolSettingsUpdateRequest(boolean enabled) {
}
