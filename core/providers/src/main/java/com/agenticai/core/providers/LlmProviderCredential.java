package com.agenticai.core.providers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * A stored, encrypted LLM provider credential.
 *
 * <p>Single-tenant for the MVP (Stage 2): one row per {@link ProviderName}. A {@code tenant_id}
 * column and a (tenant_id, provider_name) unique constraint get added when multi-tenancy lands (V1
 * roadmap) — deliberately not modeled here yet to avoid guessing at the tenancy shape early.
 *
 * <p>{@code encryptedApiKey} is ciphertext only (AES-256-GCM via {@link
 * com.agenticai.core.crypto.EncryptionService}) — the plaintext key is never persisted and this
 * entity must never be serialized directly in an API response; use {@link
 * com.agenticai.core.providers.dto.ProviderResponse}.
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@EntityListeners(ProviderCredentialEvictionListener.class)
@Table(
        name = "llm_provider_credential",
        uniqueConstraints = @UniqueConstraint(columnNames = "provider_name"))
public class LlmProviderCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_name", nullable = false, length = 32)
    private ProviderName providerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProviderStatus status;

    @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected LlmProviderCredential() {
        // JPA
    }

    public LlmProviderCredential(
            ProviderName providerName, String encryptedApiKey, ProviderStatus status) {
        this.providerName = providerName;
        this.encryptedApiKey = encryptedApiKey;
        this.status = status;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastValidatedAt = now;
    }

    public void reactivate(String encryptedApiKey, ProviderStatus status) {
        this.encryptedApiKey = encryptedApiKey;
        this.status = status;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = Instant.now();
    }

    /**
     * Mirrors {@code E2bCredential#deactivate}/{@code SarvamCredential#deactivate} - marks the row
     * INACTIVE rather than deleting it, so a tenant-admin can see what was configured before.
     */
    public void deactivate() {
        this.status = ProviderStatus.INACTIVE;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ProviderName getProviderName() {
        return providerName;
    }

    public ProviderStatus getStatus() {
        return status;
    }

    public String getEncryptedApiKey() {
        return encryptedApiKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastValidatedAt() {
        return lastValidatedAt;
    }
}
