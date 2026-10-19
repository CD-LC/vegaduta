package com.agenticai.core.sarvam;

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
 * A stored, encrypted Sarvam AI API key - BYOK, same shape as {@link
 * com.agenticai.core.omnichannel.twilio.TwilioCredential}/ {@code LlmProviderCredential}/{@code
 * GoogleCredential}: one active row "per tenant" - no {@code tenant_id} column needed at all, since
 * schema-per-tenant Hibernate multi-tenancy means the table this entity maps to is a physically
 * different table per tenant schema. Finding "the one active row" (see {@link
 * SarvamCredentialRepository#findFirstByOrderByCreatedAtAsc}) is all any query here ever needs -
 * multi-tenancy is handled entirely by which schema the current connection is pointed at.
 *
 * <p>{@code encryptedApiKey} is ciphertext only (AES-256-GCM via the existing {@link
 * com.agenticai.core.crypto.EncryptionService} - the same service every other provider/channel
 * credential in this codebase uses, no separate crypto path needed). The plaintext key is never
 * persisted and this entity must never be serialized directly in an API response - use {@link
 * com.agenticai.core.sarvam.dto.SarvamCredentialResponse}.
 *
 * <p>Per the Sarvam Integration Build Plan's Stage 1 scope: this stage only proves a tenant can
 * store and resolve a working credential - no actual call to {@code api.sarvam.ai} happens here yet
 * (that starts in Stage 2's chat completion provider and Stage 3's translation/STT/TTS clients,
 * both of which resolve their key through {@link SarvamCredentialService}).
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(name = "sarvam_credential")
public class SarvamCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SarvamCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SarvamCredential() {
        // JPA
    }

    public SarvamCredential(String encryptedApiKey) {
        this.encryptedApiKey = encryptedApiKey;
        this.status = SarvamCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Re-activation with a new key, mirroring {@code TwilioCredential#reactivate} - the same row is
     * reused rather than a new one inserted, so history (createdAt) is preserved across a key
     * rotation.
     */
    public void reactivate(String encryptedApiKey) {
        this.encryptedApiKey = encryptedApiKey;
        this.status = SarvamCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        this.status = SarvamCredentialStatus.INACTIVE;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEncryptedApiKey() {
        return encryptedApiKey;
    }

    public SarvamCredentialStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
