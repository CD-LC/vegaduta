package com.agenticai.core.providers;

import com.agenticai.core.providers.dto.ProviderResponse;
import java.util.Optional;

/**
 * SPI for an LLM provider whose BYOK credential is NOT stored in {@code
 * llm_provider_credential} but in a vendor module's own table (ADR-0011 phase
 * 2). Replaces the hard-coded {@code if (providerName == ProviderName.SARVAM)}
 * branch that used to live in {@link ProviderCredentialResolver}, and the
 * matching hard-coded Sarvam merge in {@link ProviderService#listAll()}: the
 * vendor module contributes a bean implementing this interface and the kernel
 * looks it up by {@link #providerName()}.
 *
 * <p>Today's only implementation is {@code sarvam.SarvamProviderCredentialSource}
 * (one Sarvam key covers chat, STT, TTS and translate, so a tenant activates
 * Sarvam once in {@code sarvam_credential}).
 */
public interface ExternalProviderCredentialSource {

    /** The provider this source owns. At most one source per provider. */
    ProviderName providerName();

    /**
     * The decrypted API key of the tenant's ACTIVE credential, for immediate
     * single-call use (never cache or log it). Throws the vendor's own
     * "not configured" exception when nothing is active - it propagates
     * unchanged so {@code GlobalExceptionHandler}'s existing mapping applies.
     */
    String resolveActiveApiKey();

    /**
     * The tenant's credential in the shape {@link ProviderService#listAll()}
     * returns, or empty when the tenant never configured this provider.
     */
    Optional<ProviderResponse> currentCredential();
}
