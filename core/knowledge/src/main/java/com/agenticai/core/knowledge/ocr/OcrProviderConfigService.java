package com.agenticai.core.knowledge.ocr;

import com.agenticai.core.crypto.EncryptionService;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-admin BYOK activation for cloud OCR, mirroring {@code
 * SarvamCredentialService}'s shape exactly: encrypt at rest, never return
 * the plaintext key, one active row per tenant schema. No validate-before-persist
 * call to the provider's API happens here (unlike Sarvam Stage 1->2) - a bad
 * key surfaces the first time an image is actually ingested, as a sidecar
 * extraction failure, which is an acceptable v1 tradeoff given OCR ingestion
 * is not a synchronous user-facing call path the way chat is.
 */
@Service
public class OcrProviderConfigService {

    private final OcrProviderConfigRepository repository;
    private final EncryptionService encryptionService;

    public OcrProviderConfigService(OcrProviderConfigRepository repository, EncryptionService encryptionService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
    }

    @Transactional
    public void activate(OcrProvider provider, String apiKey) {
        String encrypted = encryptionService.encrypt(apiKey);
        OcrProviderConfig config = repository.findFirstByOrderByCreatedAtAsc()
            .map(existing -> {
                existing.reactivate(provider, encrypted);
                return existing;
            })
            .orElseGet(() -> new OcrProviderConfig(provider, encrypted));
        repository.save(config);
    }

    @Transactional
    public void deactivate() {
        repository.findFirstByOrderByCreatedAtAsc().ifPresent(config -> {
            config.deactivate();
            repository.save(config);
        });
    }

    /** Provider name only - never the key - for the tenant-admin settings UI to show what's configured. */
    @Transactional(readOnly = true)
    public Optional<OcrProvider> currentProvider() {
        return repository.findFirstByOrderByCreatedAtAsc()
            .filter(OcrProviderConfig::isActive)
            .map(OcrProviderConfig::getProvider);
    }

    /**
     * Resolves the active, decrypted provider/key pair for {@code
     * SidecarClient} to pass through to the sidecar's {@code /extract} call.
     * Empty (not an exception) when nothing is configured or it's inactive -
     * "else use open source OCR reading library" means an unconfigured
     * tenant is the expected, working default, not an error state.
     */
    @Transactional(readOnly = true)
    public Optional<ActiveOcrConfig> resolveActive() {
        return repository.findFirstByOrderByCreatedAtAsc()
            .filter(OcrProviderConfig::isActive)
            .map(config -> new ActiveOcrConfig(config.getProvider(), encryptionService.decrypt(config.getEncryptedApiKey())));
    }
}
