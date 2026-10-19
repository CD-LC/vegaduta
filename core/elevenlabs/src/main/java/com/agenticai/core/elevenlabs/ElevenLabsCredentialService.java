package com.agenticai.core.elevenlabs;

import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.elevenlabs.dto.ElevenLabsCredentialActivationRequest;
import com.agenticai.core.elevenlabs.dto.ElevenLabsCredentialResponse;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BYOK activation for ElevenLabs, mirroring {@code SarvamCredentialService}'s shape: encrypt at
 * rest, never return the plaintext key, validate before persisting.
 *
 * <p>Every method here is naturally tenant-scoped by schema-per-tenant multi-tenancy - {@link
 * ElevenLabsCredentialRepository#findFirstByOrderByCreatedAtAsc()} finds "the one active row"
 * within whatever schema the current connection is pointed at, a different physical table per
 * tenant, not a shared one filtered by a tenant column.
 */
@Service
public class ElevenLabsCredentialService {

    private final ElevenLabsCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final ElevenLabsCredentialValidator validator;

    public ElevenLabsCredentialService(
            ElevenLabsCredentialRepository repository,
            EncryptionService encryptionService,
            ElevenLabsCredentialValidator validator) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.validator = validator;
    }

    @Transactional
    public ElevenLabsCredentialResponse activate(ElevenLabsCredentialActivationRequest request) {
        // Throws ElevenLabsCredentialValidationException on a rejected/unverifiable
        // key - nothing is persisted in that case, same contract every other
        // provider's activation path gives.
        validator.validate(request.apiKey());

        String encryptedApiKey = encryptionService.encrypt(request.apiKey());

        ElevenLabsCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .map(
                                existing -> {
                                    existing.reactivate(encryptedApiKey);
                                    return existing;
                                })
                        .orElseGet(() -> new ElevenLabsCredential(encryptedApiKey));

        return ElevenLabsCredentialResponse.from(repository.save(credential));
    }

    @Transactional(readOnly = true)
    public Optional<ElevenLabsCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(ElevenLabsCredentialResponse::from);
    }

    /**
     * Cheap, non-throwing gate for {@code AgentToolProvider#resolveElevenLabsTools} - never {@link
     * #resolveActiveApiKey}, which throws.
     */
    public boolean isActive() {
        return HotpathLookups.tenant(
                "elevenlabs.active",
                () ->
                        repository
                                .findFirstByOrderByCreatedAtAsc()
                                .filter(c -> c.getStatus() == ElevenLabsCredentialStatus.ACTIVE)
                                .isPresent());
    }

    /**
     * Marks the stored credential {@link ElevenLabsCredentialStatus#INACTIVE} rather than deleting
     * it, so {@link #current} can still show what was configured. A no-op if nothing was ever
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
     * Resolves the active plaintext API key for internal use by {@link ElevenLabsClient} only -
     * never exposed via a DTO/controller. Throws {@link ElevenLabsNotConfiguredException} if no
     * credential is active.
     */
    @Transactional(readOnly = true)
    public String resolveActiveApiKey() {
        ElevenLabsCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .filter(c -> c.getStatus() == ElevenLabsCredentialStatus.ACTIVE)
                        .orElseThrow(ElevenLabsNotConfiguredException::new);
        return encryptionService.decrypt(credential.getEncryptedApiKey());
    }
}
