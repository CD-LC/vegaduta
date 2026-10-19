package com.agenticai.core.sdlc;

import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.sdlc.dto.E2bCredentialActivationRequest;
import com.agenticai.core.sdlc.dto.E2bCredentialResponse;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BYOK activation for E2B, mirroring {@code SarvamCredentialService}'s shape: encrypt at rest,
 * never return the plaintext key.
 *
 * <p>Deliberately does <b>not</b> validate the key against E2B's API before persisting - unlike
 * Sarvam Stage 2's {@code SarvamCredentialValidator}, there is no equivalent validator here yet. A
 * cheap validation call is possible (E2B's {@code GET /sandboxes} is deprecated but still
 * functional and returns 401 on a bad key - see {@link E2bSandboxClient#listSandboxes}), but wiring
 * "validate before persist" is left for a later stage rather than done half-verified in this pass,
 * consistent with this codebase's "state the gap plainly, don't half-fake the guarantee"
 * convention.
 *
 * <p>Every method here is naturally tenant-scoped by schema-per-tenant multi-tenancy - {@link
 * E2bCredentialRepository#findFirstByOrderByCreatedAtAsc()} finds "the one active row" within
 * whatever schema the current connection is pointed at, a different physical table per tenant, not
 * a shared one filtered by a tenant column.
 */
@Service
public class E2bCredentialService {

    private final E2bCredentialRepository repository;
    private final EncryptionService encryptionService;

    public E2bCredentialService(
            E2bCredentialRepository repository, EncryptionService encryptionService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
    }

    @Transactional
    public E2bCredentialResponse activate(E2bCredentialActivationRequest request) {
        String encryptedApiKey = encryptionService.encrypt(request.apiKey());

        E2bCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .map(
                                existing -> {
                                    existing.reactivate(encryptedApiKey);
                                    return existing;
                                })
                        .orElseGet(() -> new E2bCredential(encryptedApiKey));

        return E2bCredentialResponse.from(repository.save(credential));
    }

    @Transactional(readOnly = true)
    public Optional<E2bCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(E2bCredentialResponse::from);
    }

    public boolean isActive() {
        return HotpathLookups.tenant(
                "e2b.active",
                () ->
                        repository
                                .findFirstByOrderByCreatedAtAsc()
                                .filter(c -> c.getStatus() == E2bCredentialStatus.ACTIVE)
                                .isPresent());
    }

    /**
     * #1071 - cheap, non-throwing check for whether {@link #resolveCodeExecutionTemplateId} would
     * succeed right now, mirroring {@link #isActive}'s "boolean check, not the throwing resolve
     * method" shape. Used by {@code AgentToolProvider#resolveE2bTools} to decide whether to offer
     * the {@code run_code} tool at all, without invoking (and having to catch) {@link
     * E2bNotConfiguredException}.
     */
    @Transactional(readOnly = true)
    public boolean hasCodeExecutionTemplate() {
        return repository
                .findFirstByOrderByCreatedAtAsc()
                .filter(c -> c.getStatus() == E2bCredentialStatus.ACTIVE)
                .map(E2bCredential::getCodeExecutionTemplateId)
                .filter(id -> !id.isBlank())
                .isPresent();
    }

    /**
     * Marks the stored credential {@link E2bCredentialStatus#INACTIVE} rather than deleting it, so
     * {@link #current} can still show what was configured. A no-op if nothing was ever activated.
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
     * Resolves the active plaintext API key for internal use by {@link E2bSandboxClient} only -
     * never exposed via a DTO/controller. Throws {@link E2bNotConfiguredException} if no credential
     * is active, so a caller three layers deep gets a clear "not configured" message instead of a
     * null-pointer.
     */
    @Transactional(readOnly = true)
    public String resolveActiveApiKey() {
        E2bCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .filter(c -> c.getStatus() == E2bCredentialStatus.ACTIVE)
                        .orElseThrow(E2bNotConfiguredException::new);
        return encryptionService.decrypt(credential.getEncryptedApiKey());
    }

    /**
     * Master Roadmap 2026 item #14 - records which E2B template (tenant-built via {@code e2b
     * template build} from core/sdlc/e2b-template/) exposes our documented {@code /run} HTTP
     * contract. Requires an active credential to already exist (you can't point a template at
     * nothing).
     */
    @Transactional
    public E2bCredentialResponse configureCodeExecutionTemplate(String templateId) {
        E2bCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .filter(c -> c.getStatus() == E2bCredentialStatus.ACTIVE)
                        .orElseThrow(E2bNotConfiguredException::new);
        credential.updateCodeExecutionTemplateId(templateId);
        return E2bCredentialResponse.from(repository.save(credential));
    }

    /**
     * Resolves the configured code-execution template ID for internal use by {@link
     * E2bCodeExecutionClient} only. Throws {@link E2bNotConfiguredException} with a
     * template-specific message (distinct from the no-credential-at-all message {@link
     * #resolveActiveApiKey()} throws) when a credential is active but no template has been set yet.
     */
    @Transactional(readOnly = true)
    public String resolveCodeExecutionTemplateId() {
        E2bCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .filter(c -> c.getStatus() == E2bCredentialStatus.ACTIVE)
                        .orElseThrow(E2bNotConfiguredException::new);
        String templateId = credential.getCodeExecutionTemplateId();
        if (templateId == null || templateId.isBlank()) {
            throw new E2bNotConfiguredException(
                    "No code-execution template configured - PUT"
                            + " /api/sdlc/credential/code-execution-template first (see"
                            + " core/sdlc/e2b-template/README.md).");
        }
        return templateId;
    }
}
