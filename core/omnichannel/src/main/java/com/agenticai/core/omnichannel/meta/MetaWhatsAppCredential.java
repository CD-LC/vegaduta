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
 * A tenant's own direct Meta WhatsApp Business Platform (Cloud API)
 * connection - the alternative to {@link com.agenticai.core.omnichannel.twilio.TwilioCredential}
 * for tenants who'd rather register their own Meta App/WhatsApp Business
 * Account than go through Twilio as a BSP (Business Solution Provider).
 * Same shape and same reasoning as {@code TwilioCredential}: one active row
 * per tenant schema (schema-per-tenant handles isolation, no tenant column
 * needed), ciphertext-only secrets via {@link com.agenticai.core.crypto.EncryptionService}.
 *
 * <p>Two secrets, not one, because Meta's Cloud API needs both: {@code
 * encryptedAccessToken} (a System User permanent token, sent as a Bearer
 * header on every outbound Graph API call) and {@code encryptedAppSecret}
 * (the tenant's own Meta App's secret, used only to verify {@code
 * X-Hub-Signature-256} on inbound webhook payloads - see {@link
 * MetaWhatsAppSignatureValidator}). Twilio only needed one (the auth token
 * doubles as both the API credential and the HMAC key for its signature
 * scheme) - Meta's model keeps these separate.
 */
@Entity
@Table(name = "meta_whatsapp_credential")
public class MetaWhatsAppCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "phone_number_id", nullable = false, length = 64)
    private String phoneNumberId;

    @Column(name = "waba_id", nullable = false, length = 64)
    private String wabaId;

    @Column(name = "encrypted_access_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(name = "encrypted_app_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedAppSecret;

    @Column(name = "display_phone_number", length = 32)
    private String displayPhoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MetaWhatsAppCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected MetaWhatsAppCredential() {
        // JPA
    }

    public MetaWhatsAppCredential(
        String phoneNumberId, String wabaId, String encryptedAccessToken, String encryptedAppSecret, String displayPhoneNumber
    ) {
        this.phoneNumberId = phoneNumberId;
        this.wabaId = wabaId;
        this.encryptedAccessToken = encryptedAccessToken;
        this.encryptedAppSecret = encryptedAppSecret;
        this.displayPhoneNumber = displayPhoneNumber;
        this.status = MetaWhatsAppCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastValidatedAt = now;
    }

    public void reactivate(
        String phoneNumberId, String wabaId, String encryptedAccessToken, String encryptedAppSecret, String displayPhoneNumber
    ) {
        this.phoneNumberId = phoneNumberId;
        this.wabaId = wabaId;
        this.encryptedAccessToken = encryptedAccessToken;
        this.encryptedAppSecret = encryptedAppSecret;
        this.displayPhoneNumber = displayPhoneNumber;
        this.status = MetaWhatsAppCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = Instant.now();
    }

    /** Tenant-initiated disconnect - keeps the row so the console can still show what was connected; every consumer resolves ACTIVE-only, so this alone stops all use. */
    public void disconnect() {
        this.status = MetaWhatsAppCredentialStatus.DISCONNECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getPhoneNumberId() {
        return phoneNumberId;
    }

    public String getWabaId() {
        return wabaId;
    }

    public String getEncryptedAccessToken() {
        return encryptedAccessToken;
    }

    public String getEncryptedAppSecret() {
        return encryptedAppSecret;
    }

    public String getDisplayPhoneNumber() {
        return displayPhoneNumber;
    }

    public MetaWhatsAppCredentialStatus getStatus() {
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
