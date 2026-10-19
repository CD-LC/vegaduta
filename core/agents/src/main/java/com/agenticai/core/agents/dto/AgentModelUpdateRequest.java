package com.agenticai.core.agents.dto;

import com.agenticai.core.providers.ProviderName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * PATCH /api/agents/{id}/model - Unlimited-plan (and any un-locked agent's)
 * ability to change which provider/model an existing agent talks to, after
 * creation. Gated through the same {@code EditLockPolicy}/{@code
 * assertEditable} check every other after-creation PATCH endpoint on this
 * resource already uses (see {@code AgentService#assertEditable}'s javadoc -
 * PREMIUM/UNLIMITED tenants are never locked, FREE tenants get a 24h grace
 * window then must upgrade or duplicate). Both fields required together,
 * same "always update as a pair, never partially" reasoning as {@code
 * AgentGenerationParamsUpdateRequest} - a providerName with no matching
 * model (or vice versa) is not a valid agent state.
 */
public record AgentModelUpdateRequest(
    @NotNull(message = "providerName is required") ProviderName providerName,
    @NotBlank(message = "model is required") String model
) {
    /**
     * Trims {@code model} before {@code @NotBlank}, the catalog lookup, or the
     * entity write ever sees it - same compact-constructor posture, and the same
     * copy-paste cause, as {@code ProviderActivationRequest}'s trim of {@code
     * apiKey}. A model id with a stray leading/trailing space is not merely
     * cosmetic: it is sent verbatim to the provider and rejected, which is
     * exactly how a hosted-pool row holding {@code " gemini-2.0-flash-lite"}
     * turned into a 404 on every turn. {@code @NotBlank} does not catch it -
     * a padded id still reads as non-blank - and for a BYOK tenant the catalog
     * is only a suggestion list (see {@code AgentService#updateModel}), so
     * nothing downstream would have rejected it either.
     */
    public AgentModelUpdateRequest {
        if (model != null) {
            model = model.trim();
        }
    }
}
