package com.agenticai.core.agents.dto;

import com.agenticai.core.providers.ProviderName;

/**
 * PATCH /api/agents/{id}/fallback - direct feedback: "set a fallback
 * provider/model so future provider outages degrade gracefully instead of
 * failing the run." Lets a tenant-admin configure a secondary provider/model
 * an agent falls back to when its primary provider call fails (timeout,
 * rate limit, 5xx, network error, or a 404 model-unavailable response - see
 * {@code ProviderErrorTranslator} and {@code AgentChatService}'s
 * {@code callWithProviderFallback} for the classification + retry path).
 *
 * <p>Unlike {@link AgentModelUpdateRequest}, {@code providerName} is
 * nullable on purpose: sending {@code {"providerName": null}} is how a
 * tenant-admin clears a previously-configured fallback and reverts to
 * today's default (a provider failure surfaces to the user/workflow). When
 * {@code providerName} is non-null, {@code model} is required - see
 * {@link com.agenticai.core.agents.core.AgentService#setFallbackProvider}.
 */
public record AgentFallbackUpdateRequest(
    ProviderName providerName,
    String model
) {
    /** Trims {@code model} for the same reason {@link AgentModelUpdateRequest} does - a padded model id is sent verbatim to the provider and rejected. */
    public AgentFallbackUpdateRequest {
        if (model != null) {
            model = model.trim();
        }
    }
}
