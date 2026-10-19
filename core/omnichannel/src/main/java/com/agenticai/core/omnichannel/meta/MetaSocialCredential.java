package com.agenticai.core.omnichannel.meta;

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
 * A tenant's connected Facebook Page - the single credential that unlocks
 * BOTH Facebook Messenger and Instagram DMs, mirroring how Meta's own Graph
 * API actually works: a Page Access Token is the one credential, and
 * Instagram messaging is only reachable through a Page that has an
 * Instagram Professional (Business/Creator) account linked to it. This is
 * why there's one entity/table here, not two - {@code
 * instagramBusinessAccountId} being null simply means "this tenant only
 * wants Messenger," not "no credential yet."
 *
 * <p>Same shape and reasoning as {@link MetaWhatsAppCredential}: one active
 * row per tenant schema (schema-per-tenant handles isolation), ciphertext-
 * only secrets via {@link com.agenticai.core.crypto.EncryptionService}. Two
 * secrets for the same reason WhatsApp's Meta credential has two: {@code
 * encryptedPageAccessToken} (Bearer header on every outbound Graph API
 * call) and {@code encryptedAppSecret} (HMAC key for verifying inbound
 * {@code X-Hub-Signature-256}, via the same {@link
 * MetaWhatsAppSignatureValidator} WhatsApp's Meta path already uses - the
 * validator is signature-scheme-generic, not WhatsApp-specific, despite its
 * name).
 */
@Entity
@Table(name = "meta_social_credential")
public class MetaSocialCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "page_id", nullable = false, length = 64)
    private String pageId;

    @Column(name = "page_name", length = 255)
    private String pageName;

    @Column(name = "encrypted_page_access_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedPageAccessToken;

    @Column(name = "encrypted_app_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedAppSecret;

    @Column(name = "instagram_business_account_id", length = 64)
    private String instagramBusinessAccountId;

    @Column(name = "instagram_username", length = 255)
    private String instagramUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MetaSocialCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected MetaSocialCredential() {
        // JPA
    }

    public MetaSocialCredential(
        String pageId, String pageName, String encryptedPageAccessToken, String encryptedAppSecret,
        String instagramBusinessAccountId, String instagramUsername
    ) {
        this.pageId = pageId;
        this.pageName = pageName;
        this.encryptedPageAccessToken = encryptedPageAccessToken;
        this.encryptedAppSecret = encryptedAppSecret;
        this.instagramBusinessAccountId = instagramBusinessAccountId;
        this.instagramUsername = instagramUsername;
        this.status = MetaSocialCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastValidatedAt = now;
    }

    public void reactivate(
        String pageId, String pageName, String encryptedPageAccessToken, String encryptedAppSecret,
        String instagramBusinessAccountId, String instagramUsername
    ) {
        this.pageId = pageId;
        this.pageName = pageName;
        this.encryptedPageAccessToken = encryptedPageAccessToken;
        this.encryptedAppSecret = encryptedAppSecret;
        this.instagramBusinessAccountId = instagramBusinessAccountId;
        this.instagramUsername = instagramUsername;
        this.status = MetaSocialCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = Instant.now();
    }

    public boolean hasInstagram() {
        return instagramBusinessAccountId != null && !instagramBusinessAccountId.isBlank();
    }

    /** Tenant-initiated disconnect - keeps the row so the console can still show what was connected; every consumer resolves ACTIVE-only, so this alone stops all use. */
    public void disconnect() {
        this.status = MetaSocialCredentialStatus.DISCONNECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getPageId() {
        return pageId;
    }

    public String getPageName() {
        return pageName;
    }

    public String getEncryptedPageAccessToken() {
        return encryptedPageAccessToken;
    }

    public String getEncryptedAppSecret() {
        return encryptedAppSecret;
    }

    public String getInstagramBusinessAccountId() {
        return instagramBusinessAccountId;
    }

    public String getInstagramUsername() {
        return instagramUsername;
    }

    public MetaSocialCredentialStatus getStatus() {
        return status;
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
