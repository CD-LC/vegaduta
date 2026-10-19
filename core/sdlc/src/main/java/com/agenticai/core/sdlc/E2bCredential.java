package com.agenticai.core.sdlc;

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
 * A stored, encrypted E2B API key - BYOK, same shape as {@link
 * com.agenticai.core.sarvam.SarvamCredential}/{@code TwilioCredential}/ {@code GoogleCredential}:
 * one active row "per tenant" - no {@code tenant_id} column needed, since schema-per-tenant
 * Hibernate multi-tenancy means the table this entity maps to is a physically different table per
 * tenant schema. Finding "the one active row" (see {@link
 * E2bCredentialRepository#findFirstByOrderByCreatedAtAsc}) is all any query here ever needs.
 *
 * <p>{@code encryptedApiKey} is ciphertext only (AES-256-GCM via the existing {@link
 * com.agenticai.core.crypto.EncryptionService}, the same service every other provider/channel
 * credential in this codebase uses). The plaintext key is never persisted and this entity must
 * never be serialized directly in an API response - use {@link
 * com.agenticai.core.sdlc.dto.E2bCredentialResponse}.
 */
@com.agenticai.core.security.TenantConfigEntity
@Entity
@Table(name = "e2b_credential")
public class E2bCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "encrypted_api_key", nullable = false, columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private E2bCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Master Roadmap 2026 item #14 - which E2B template (built and pushed by the tenant themselves
     * via {@code e2b template create}, see core/sdlc/e2b-template/README.md) exposes our documented
     * {@code /run} HTTP contract for code execution. Nullable: manual sandbox create/list/kill
     * (#849) works without this; only {@link
     * com.agenticai.core.sdlc.E2bCodeExecutionClient#runCode} requires it.
     */
    @Column(name = "code_execution_template_id", length = 64)
    private String codeExecutionTemplateId;

    protected E2bCredential() {
        // JPA
    }

    public E2bCredential(String encryptedApiKey) {
        this.encryptedApiKey = encryptedApiKey;
        this.status = E2bCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Re-activation with a new key - the same row is reused rather than a new one inserted, so
     * history (createdAt) is preserved across a key rotation.
     */
    public void reactivate(String encryptedApiKey) {
        this.encryptedApiKey = encryptedApiKey;
        this.status = E2bCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        this.status = E2bCredentialStatus.INACTIVE;
        this.updatedAt = Instant.now();
    }

    /** Blank/null clears it back to unconfigured (falls back to manual sandbox lifecycle only). */
    public void updateCodeExecutionTemplateId(String templateId) {
        this.codeExecutionTemplateId =
                (templateId == null || templateId.isBlank()) ? null : templateId.trim();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEncryptedApiKey() {
        return encryptedApiKey;
    }

    public E2bCredentialStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getCodeExecutionTemplateId() {
        return codeExecutionTemplateId;
    }
}
