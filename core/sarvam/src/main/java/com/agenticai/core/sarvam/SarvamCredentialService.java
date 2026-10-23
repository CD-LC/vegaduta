package com.agenticai.core.sarvam;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.sarvam.dto.SarvamCredentialActivationRequest;
import com.agenticai.core.sarvam.dto.SarvamCredentialResponse;
import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BYOK activation for Sarvam AI, mirroring {@code TwilioCredentialService}'s shape: encrypt at
 * rest, never return the plaintext key.
 *
 * <p>Stage 1 deliberately did <b>not</b> validate the key with a real call to {@code api.sarvam.ai}
 * before persisting - this module had no Sarvam API client yet. Stage 2 (chat completion) adds one:
 * {@link SarvamCredentialValidator#validate} runs before every {@link #activate}, the same
 * validate-before-persist guarantee every other provider's activation path gets (see that class's
 * javadoc for why it's a standalone class here rather than {@code
 * core.providers.validation.ProviderValidator}).
 *
 * <p>Every method here is naturally tenant-scoped by schema-per-tenant multi-tenancy - {@link
 * SarvamCredentialRepository#findFirstByOrderByCreatedAtAsc()} finds "the one active row" within
 * whatever schema the current connection is pointed at, a different physical table per tenant, not
 * a shared one filtered by a tenant column. No {@code TenantContext}/{@code TenantSchemaResolver}
 * call is needed directly in this service for that reason - the isolation is structural, not code
 * the service enforces itself (same as {@code TwilioCredentialService}'s repository calls).
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class SarvamCredentialService {

    private final SarvamCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final SarvamCredentialValidator validator;

    public SarvamCredentialService(
            SarvamCredentialRepository repository,
            EncryptionService encryptionService,
            SarvamCredentialValidator validator) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.validator = validator;
    }

    @Transactional
    public SarvamCredentialResponse activate(SarvamCredentialActivationRequest request) {
        // Throws SarvamCredentialValidationException on a rejected/unverifiable
        // key - nothing is persisted in that case, same contract ProviderService
        // gives every other provider.
        validator.validate(request.apiKey());

        String encryptedApiKey = encryptionService.encrypt(request.apiKey());

        SarvamCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .map(
                                existing -> {
                                    existing.reactivate(encryptedApiKey);
                                    return existing;
                                })
                        .orElseGet(() -> new SarvamCredential(encryptedApiKey));

        return SarvamCredentialResponse.from(repository.save(credential));
    }

    @Transactional(readOnly = true)
    public Optional<SarvamCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(SarvamCredentialResponse::from);
    }

    public boolean isActive() {
        return HotpathLookups.tenant(
                "sarvam.active",
                () ->
                        repository
                                .findFirstByOrderByCreatedAtAsc()
                                .filter(c -> c.getStatus() == SarvamCredentialStatus.ACTIVE)
                                .isPresent());
    }

    /**
     * Marks the stored credential {@link SarvamCredentialStatus#INACTIVE} rather than deleting it,
     * so {@link #current} can still show what was configured. A no-op if nothing was ever
     * activated.
     */
    @Transactional
    public void deactivate() {
        repository
                .findFirstByOrderByCreatedAtAsc()
                .ifPresent(
                        credential -> {
                            credential.deactivate();
                            repository.save(credential);
                        });
    }

    /**
     * Resolves the active plaintext API key for internal use by later stages' Sarvam API clients
     * only - never exposed via a DTO/controller. Throws {@link SarvamNotConfiguredException} if no
     * credential is active, so a caller three layers deep gets a clear "not configured" message
     * instead of a null-pointer.
     */
    @Transactional(readOnly = true)
    public String resolveActiveApiKey() {
        SarvamCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .filter(c -> c.getStatus() == SarvamCredentialStatus.ACTIVE)
                        .orElseThrow(SarvamNotConfiguredException::new);
        return encryptionService.decrypt(credential.getEncryptedApiKey());
    }
}
