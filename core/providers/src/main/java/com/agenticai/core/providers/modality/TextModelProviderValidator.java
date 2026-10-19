package com.agenticai.core.providers.modality;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Validates that an agent's text model provider/model selection is valid for
 * this deployment. Used by {@link com.agenticai.core.agents.core.AgentService} when
 * an agent is created or updated.
 *
 * <p>Key invariants:
 * <ul>
 *   <li>Provider must be implemented and available on this deployment</li>
 *   <li>Provider and model must be set together (both non-null or both null)</li>
 *   <li>Model must be non-blank if provider is set</li>
 * </ul>
 */
@Component
public class TextModelProviderValidator {

    private final TextModelProviderFactory providerFactory;

    public TextModelProviderValidator(TextModelProviderFactory providerFactory) {
        this.providerFactory = providerFactory;
    }

    /**
     * Validates a text model provider/model pair selected for an agent.
     * Throws {@link IllegalArgumentException} if the selection is invalid.
     *
     * @param provider the text provider enum (or null to use agent's primary provider)
     * @param model the model name (or null)
     * @throws IllegalArgumentException if:
     *         - provider and model are not both null or both non-null
     *         - model is blank when provider is non-null
     *         - provider is not available on this deployment
     */
    public void validate(TextModelProvider provider, String model) {
        // Both null is OK - means use agent's primary provider/model
        if (provider == null && model == null) {
            return;
        }

        // Both must be set together
        if (provider == null || !StringUtils.hasText(model)) {
            throw new IllegalArgumentException(
                "Text model provider and model must be set together. " +
                "If you don't want to explicitly select a provider, leave both null to use the agent's primary provider."
            );
        }

        // Provider must be available
        if (!providerFactory.isAvailable(provider)) {
            String reason = providerFactory.getUnavailabilityReason(provider);
            throw new IllegalArgumentException(
                "Cannot select text model provider '" + provider.getProviderId() + "': " + reason
            );
        }

        // Model should be non-blank (already checked above, but be explicit)
        if (model.isBlank()) {
            throw new IllegalArgumentException(
                "Text model cannot be blank when a provider is selected."
            );
        }
    }

    /**
     * Convenience method: checks if a provider/model selection would be valid.
     * Returns false rather than throwing.
     */
    public boolean isValid(TextModelProvider provider, String model) {
        try {
            validate(provider, model);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
