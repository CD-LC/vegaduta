package com.agenticai.core.providers.validation;

import com.agenticai.core.providers.ProviderName;

/**
 * Validates an API key against a specific LLM provider using a lightweight,
 * non-billed call (e.g. a "list models" endpoint), not a real generation call.
 * One implementation per supported {@link ProviderName}.
 */
public interface ProviderValidator {

    ProviderName supportedProvider();

    /**
     * @throws ProviderValidationException if the key is rejected by the provider,
     *         or the validation call itself fails (network, timeout, etc).
     */
    void validate(String apiKey);
}
