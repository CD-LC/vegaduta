package com.agenticai.core.knowledge.ocr;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A tenant's stored, encrypted cloud-OCR credential - BYOK, same
 * one-active-row-per-tenant-schema shape as {@code SarvamCredential}/{@code
 * TwilioCredential} (see {@link OcrProviderConfigRepository}'s javadoc for
 * why no {@code tenant_id} column is needed). When no row exists, or {@code
 * active} is false, callers fall back to the sidecar's built-in Tesseract
 * path - see {@code OcrProviderConfigService#resolveActive}.
 */
@Entity
@Table(name = "ocr_provider_config")
public class OcrProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OcrProvider provider;

    @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OcrProviderConfig() {
        // JPA
    }

    public OcrProviderConfig(OcrProvider provider, String encryptedApiKey) {
        this.provider = provider;
        this.encryptedApiKey = encryptedApiKey;
        this.active = true;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Re-activation with a (possibly new) provider/key, same row reused - mirrors {@code SarvamCredential#reactivate}. */
    public void reactivate(OcrProvider provider, String encryptedApiKey) {
        this.provider = provider;
        this.encryptedApiKey = encryptedApiKey;
        this.active = true;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        this.active = false;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public OcrProvider getProvider() {
        return provider;
    }

    public String getEncryptedApiKey() {
        return encryptedApiKey;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
