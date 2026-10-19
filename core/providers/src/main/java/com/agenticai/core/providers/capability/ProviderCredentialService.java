package com.agenticai.core.providers.capability;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.ProviderStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The generic tenant keyring over {@link ProviderCredential} - the unified
 * credential store from docs/architecture/GENERIC-PROVIDER-CAPABILITY-
 * REGISTRY-2026-08-09.md that Jev was the first provider actually wired into
 * (previously Phase 1 scaffolding: the table existed since V217 but nothing
 * read or wrote it).
 *
 * <p>{@link #activate} adds the validate-before-persist half the scaffolding
 * deferred: the {@link ProviderCredentialValidator} SPI (one bean per
 * provider, collected via {@code List} injection) verifies a candidate key
 * against the real upstream before ciphertext is ever stored, same contract
 * {@link com.agenticai.core.providers.ProviderService#activate} gives the
 * LLM-chat table. Providers without a registered validator cannot be
 * activated - there is no way to persist a key nobody can check.
 */
@Service
public class ProviderCredentialService {

    private final ProviderCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final Map<String, ProviderCredentialValidator> validatorsByProvider;

    public ProviderCredentialService(
        ProviderCredentialRepository repository,
        EncryptionService encryptionService,
        List<ProviderCredentialValidator> validators
    ) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.validatorsByProvider = validators == null ? Map.of() : validators.stream()
            .collect(Collectors.toMap(ProviderCredentialValidator::providerKey, Function.identity()));
    }

    /** The registered validators, keyed by provider key - the catalog {@code GET /api/capabilities} renders from. */
    public Map<String, ProviderCredentialValidator> validators() {
        return validatorsByProvider;
    }

    /**
     * Validate-then-activate for the generic {@code POST
     * /api/capabilities/{capability}/credential} path (and the {@code
     * /api/jev} compat shim). The validator owns the capabilities stamped
     * onto the row; {@code extraConfig} stays caller-supplied for providers
     * needing second-step config.
     */
    @Transactional
    public ProviderCredential activate(String providerKey, String plaintextSecret, String extraConfig) {
        ProviderCredentialValidator validator = validatorsByProvider.get(providerKey);
        if (validator == null) {
            throw new UnknownCapabilityException(
                "Provider '" + providerKey + "' is not registered - no credential validator exists for it.");
        }
        // Throws a CapabilityCredentialValidationException (or subtype) on a
        // rejected/unverifiable key - nothing is persisted in that case.
        validator.validate(plaintextSecret);
        return save(providerKey, validator.capabilities(), plaintextSecret, extraConfig);
    }

    /**
     * Creates a new row, or reactivates/overwrites the existing one for this
     * {@code providerKey} (mirrors {@link com.agenticai.core.providers.ProviderService#activate}'s
     * create-or-reactivate shape) - the unique index on {@code provider_key}
     * (V217) means there is at most one row per provider per tenant schema.
     */
    @Transactional
    public ProviderCredential save(String providerKey, List<String> capabilities, String plaintextSecret, String extraConfig) {
        String encrypted = encryptionService.encrypt(plaintextSecret);
        Optional<ProviderCredential> existing = repository.findByProviderKey(providerKey);
        if (existing.isPresent()) {
            ProviderCredential credential = existing.get();
            credential.reactivate(encrypted, capabilities, ProviderStatus.ACTIVE);
            return credential;
        }
        ProviderCredential credential = new ProviderCredential(
            providerKey, capabilities, encrypted, ProviderStatus.ACTIVE, extraConfig);
        return repository.save(credential);
    }

    @Transactional(readOnly = true)
    public Optional<ProviderCredential> findByProviderKey(String providerKey) {
        return repository.findByProviderKey(providerKey);
    }

    /** Decrypts and returns the secret for one credential - callers must not cache or log the result. */
    @Transactional(readOnly = true)
    public Optional<String> resolveDecryptedSecret(String providerKey) {
        return repository.findByProviderKey(providerKey)
            .filter(credential -> credential.getStatus() == ProviderStatus.ACTIVE)
            .map(credential -> encryptionService.decrypt(credential.getEncryptedSecret()));
    }

    @Transactional(readOnly = true)
    public List<ProviderCredential> findActiveByCapability(String capability) {
        return repository.findByStatusAndCapability(ProviderStatus.ACTIVE.name(), capability);
    }

    /** Mirrors {@link ProviderCredential#deactivate()} - marks INACTIVE rather than deleting. */
    @Transactional
    public void deactivate(String providerKey) {
        repository.findByProviderKey(providerKey).ifPresent(ProviderCredential::deactivate);
    }
}
