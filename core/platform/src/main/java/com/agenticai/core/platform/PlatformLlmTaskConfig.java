package com.agenticai.core.platform;

import com.agenticai.core.providers.ProviderName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Which LLM provider/model/key a single {@link PlatformTask} (a strictly
 * internal, system-level task - see that enum's javadoc) should use.
 *
 * <p>Lives in the {@code control_plane} schema, mirroring {@code
 * com.agenticai.core.tenancy.Tenant}'s exact {@code @Table(schema = ...)}
 * style - this is platform-wide configuration, not owned by any one
 * tenant's per-tenant schema.
 *
 * <p>{@code encryptedApiKey} is ciphertext only (AES-256-GCM via {@link
 * com.agenticai.core.crypto.EncryptionService}), same as {@code
 * com.agenticai.core.providers.LlmProviderCredential#encryptedApiKey} - the
 * plaintext key is never persisted and this entity must never be serialized
 * directly in an API response; see {@link PlatformLlmTaskConfigResponse}.
 */
@Entity
@Table(
    name = "platform_llm_task_config",
    schema = "control_plane",
    uniqueConstraints = @UniqueConstraint(columnNames = "task")
)
public class PlatformLlmTaskConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PlatformTask task;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_name", nullable = false, length = 32)
    private ProviderName providerName;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(name = "encrypted_api_key", columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected PlatformLlmTaskConfig() {
        // JPA
    }

    public PlatformLlmTaskConfig(PlatformTask task, ProviderName providerName, String model, String encryptedApiKey) {
        this.task = task;
        this.providerName = providerName;
        this.model = model;
        this.encryptedApiKey = encryptedApiKey;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Applies an update from {@code PlatformLlmTaskService#upsert} - {@code
     * newEncryptedApiKeyOrNull} is only applied when non-null, so an edit to
     * just the provider/model (or a "test connectivity" call that doesn't
     * resubmit a key) never wipes out an already-stored credential.
     */
    public void apply(ProviderName providerName, String model, String newEncryptedApiKeyOrNull) {
        this.providerName = providerName;
        this.model = model;
        if (newEncryptedApiKeyOrNull != null) {
            this.encryptedApiKey = newEncryptedApiKeyOrNull;
        }
        this.updatedAt = Instant.now();
    }

    public void markValidatedNow() {
        this.lastValidatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public PlatformTask getTask() {
        return task;
    }

    public ProviderName getProviderName() {
        return providerName;
    }

    public String getModel() {
        return model;
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
