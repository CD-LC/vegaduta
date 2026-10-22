package com.agenticai.core.sarvam;

import com.agenticai.core.providers.ExternalProviderCredentialSource;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderStatus;
import com.agenticai.core.providers.dto.ProviderResponse;
import com.agenticai.core.sarvam.dto.SarvamCredentialResponse;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Sarvam's contribution to the kernel credential lookup (ADR-0011 phase 2). Replaces the hard-coded
 * {@code ProviderName.SARVAM} branch that used to live in {@code
 * providers.ProviderCredentialResolver} and the {@code ProviderResponse.fromSarvam} merge in {@code
 * ProviderService#listAll()} - same calls, same mapping, now owned by the vendor module.
 *
 * <p>Sarvam's BYOK credential lives in its own {@code sarvam_credential} table, not {@code
 * llm_provider_credential}, because one key is shared by chat and the non-chat Sarvam clients
 * (translate/STT/TTS/etc.).
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class SarvamProviderCredentialSource implements ExternalProviderCredentialSource {

    private final SarvamCredentialService credentialService;

    public SarvamProviderCredentialSource(SarvamCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public ProviderName providerName() {
        return ProviderName.SARVAM;
    }

    /** Throws {@link SarvamNotConfiguredException} unchanged when nothing is active. */
    @Override
    public String resolveActiveApiKey() {
        return credentialService.resolveActiveApiKey();
    }

    @Override
    public Optional<ProviderResponse> currentCredential() {
        return credentialService.current().map(SarvamProviderCredentialSource::toProviderResponse);
    }

    /**
     * Adapts {@link SarvamCredentialResponse} to the merged provider-list shape (moved verbatim
     * from {@code ProviderResponse.fromSarvam}). Without this merge Sarvam never appeared in the
     * model/provider dropdown, which filters the list down to {@code status === "ACTIVE"}. {@link
     * SarvamCredentialStatus} only has ACTIVE/INACTIVE (activation validates synchronously and
     * never persists on failure), so INACTIVE maps to {@link ProviderStatus#INVALID} purely so the
     * frontend's two-state check behaves identically; the accurate wording still comes from {@code
     * GET /api/sarvam/credential/status}.
     */
    static ProviderResponse toProviderResponse(SarvamCredentialResponse sarvam) {
        return new ProviderResponse(
                sarvam.id(),
                ProviderName.SARVAM,
                sarvam.status() == SarvamCredentialStatus.ACTIVE
                        ? ProviderStatus.ACTIVE
                        : ProviderStatus.INVALID,
                sarvam.createdAt(),
                sarvam.updatedAt(),
                null);
    }
}
