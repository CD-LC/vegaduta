package com.agenticai.core.providers.capability;

import java.util.List;

/**
 * SPI for "is this tenant-supplied key actually valid against provider X" -
 * the generic-registry counterpart of {@code
 * com.agenticai.core.providers.validation.ProviderValidator} (which is keyed
 * by {@code ProviderName} and only covers LLM chat providers). One bean per
 * onboarded provider, collected automatically by {@link
 * ProviderCredentialService}'s {@code List} injection - registering a new
 * provider's credential path is "write one validator bean," nothing else in
 * this package needs editing.
 *
 * <p>The validator doubles as the credential descriptor for its provider:
 * {@link #capabilities()} is what gets stamped onto the {@code
 * provider_credential.capabilities} array on activation, and {@link
 * #displayName()} is what the generic {@code /api/capabilities} catalog
 * renders - a provider without a validator is a provider tenants cannot
 * activate keys for.
 */
public interface ProviderCredentialValidator {

    /** The {@code provider_credential.provider_key} this validator activates keys for - e.g. {@link ProviderKeys#TYPESAFE}. */
    String providerKey();

    /** Human-facing name for the catalog/UI - e.g. "TypeSafe Jev". */
    String displayName();

    /** Which {@link Capability} names a credential for this provider can serve (uppercase enum names, matching the design doc's {@code {'TTS','STT','TRANSLATE'}} convention) - stamped onto the row's {@code capabilities} array verbatim. */
    List<String> capabilities();

    /**
     * Verifies {@code apiKey} against the provider (validate-before-persist,
     * same contract {@code ProviderValidator#validate} gives the LLM path).
     * Throws {@link CapabilityCredentialValidationException} - or a subtype -
     * on a rejected/unverifiable key; returns normally when the key works.
     */
    void validate(String apiKey);
}
