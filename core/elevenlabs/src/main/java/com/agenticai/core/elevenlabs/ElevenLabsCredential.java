package com.agenticai.core.elevenlabs;

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
 * A stored, encrypted ElevenLabs API key - BYOK, same shape as {@link
 * com.agenticai.core.elevenlabs}'s sibling {@code SarvamCredential}: one active row per tenant, no
 * {@code tenant_id} column needed - schema-per- tenant Hibernate multi-tenancy means the table this
 * maps to is a physically different table per tenant schema already.
 *
 * <p>{@code encryptedApiKey} is ciphertext only (AES-256-GCM via {@link
 * com.agenticai.core.crypto.EncryptionService}, the same service every other provider/channel
 * credential in this codebase uses). The plaintext key is never persisted and this entity must
 * never be serialized directly - use {@link
 * com.agenticai.core.elevenlabs.dto.ElevenLabsCredentialResponse}.
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(name = "elevenlabs_credential")
public class ElevenLabsCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ElevenLabsCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ElevenLabsCredential() {
        // JPA
    }

    public ElevenLabsCredential(String encryptedApiKey) {
        this.encryptedApiKey = encryptedApiKey;
        this.status = ElevenLabsCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Re-activation with a new key - same row reused rather than a new one inserted, mirroring
     * {@code SarvamCredential#reactivate}.
     */
    public void reactivate(String encryptedApiKey) {
        this.encryptedApiKey = encryptedApiKey;
        this.status = ElevenLabsCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        this.status = ElevenLabsCredentialStatus.INACTIVE;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEncryptedApiKey() {
        return encryptedApiKey;
    }

    public ElevenLabsCredentialStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
