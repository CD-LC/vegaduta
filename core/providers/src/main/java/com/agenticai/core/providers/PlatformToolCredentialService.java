package com.agenticai.core.providers;

import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.crypto.EncryptionService;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * CRUD + decrypt for {@link PlatformToolCredential} rows - backs {@code
 * PlatformToolCredentialController}'s {@code /api/platform/tool-credentials/{toolKey}} endpoints,
 * already covered by {@code SecurityConfig}'s blanket {@code /api/platform/**} -> {@code
 * ROLE_platform-admin} rule (no new security wiring needed).
 *
 * <p>{@code resolveActiveApiKey}/{@code resolveActiveExtraConfig} are what a runtime caller (e.g. a
 * future {@code PlatformElevenLabsSpeakBackend}) reads - both return empty rather than throwing
 * when nothing is configured or the row is disabled, since "platform key not set" is an expected,
 * non-error state every caller must already handle as "fall through to no platform fallback", not a
 * 5xx.
 */
@Service
public class PlatformToolCredentialService {

    private final PlatformToolCredentialRepository repository;
    private final EncryptionService encryptionService;

    public PlatformToolCredentialService(
            PlatformToolCredentialRepository repository, EncryptionService encryptionService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
    }

    public PlatformToolCredentialStatusResponse status(String toolKey) {
        return repository
                .findById(toolKey)
                .map(
                        row ->
                                new PlatformToolCredentialStatusResponse(
                                        toolKey,
                                        row.isEnabled(),
                                        row.getExtraConfig(),
                                        row.getUpdatedAt(),
                                        row.getUpdatedBy()))
                .orElseGet(() -> PlatformToolCredentialStatusResponse.notConfigured(toolKey));
    }

    public PlatformToolCredentialStatusResponse activate(
            String toolKey, String apiKey, String extraConfig, String updatedBy) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException(
                    "An API key is required to configure this platform credential.");
        }
        String encrypted = encryptionService.encrypt(apiKey);
        String trimmedExtra = StringUtils.hasText(extraConfig) ? extraConfig.trim() : null;
        PlatformToolCredential row = repository.findById(toolKey).orElse(null);
        if (row == null) {
            row = new PlatformToolCredential(toolKey, encrypted, trimmedExtra, updatedBy);
        } else {
            row.update(encrypted, trimmedExtra, updatedBy);
        }
        repository.save(row);
        HotpathCaches.evictPlatformConfig();
        return status(toolKey);
    }

    public void deactivate(String toolKey, String updatedBy) {
        repository
                .findById(toolKey)
                .ifPresent(
                        row -> {
                            row.deactivate(updatedBy);
                            repository.save(row);
                            HotpathCaches.evictPlatformConfig();
                        });
    }

    /**
     * Empty when unconfigured or disabled - callers treat that as "no platform fallback available",
     * never an error.
     */
    public Optional<String> resolveActiveApiKey(String toolKey) {
        return cachedRow(toolKey)
                .filter(PlatformToolCredential::isEnabled)
                .map(row -> encryptionService.decrypt(row.getEncryptedApiKey()));
    }

    /**
     * The tool-specific extra field (ElevenLabs voice_id / E2B template id) - empty under the same
     * conditions as {@link #resolveActiveApiKey}.
     */
    public Optional<String> resolveActiveExtraConfig(String toolKey) {
        return cachedRow(toolKey)
                .filter(PlatformToolCredential::isEnabled)
                .map(PlatformToolCredential::getExtraConfig)
                .filter(StringUtils::hasText);
    }

    /**
     * The stored row (still encrypted) for a tool key, read through the platform lookup cache:
     * several resolvers ask for the same few rows on every chat turn. The key is decrypted per
     * call, never cached. A write through {@link #activate} or {@link #deactivate} evicts at once;
     * another pod sees it within the TTL.
     */
    private Optional<PlatformToolCredential> cachedRow(String toolKey) {
        return HotpathLookups.platform(
                "tool-credential", toolKey, () -> repository.findById(toolKey));
    }
}
