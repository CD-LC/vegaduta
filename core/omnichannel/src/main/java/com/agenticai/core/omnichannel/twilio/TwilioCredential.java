package com.agenticai.core.omnichannel.twilio;

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
 * A stored, encrypted Twilio credential - BYOK, same shape as
 * {@link com.agenticai.core.providers.LlmProviderCredential}: one active row
 * "per tenant" - no {@code tenant_id} column needed at all, since Phase 0
 * Stage 4's schema-per-tenant Hibernate multi-tenancy means the table this
 * entity maps to is a physically different table per tenant schema. This
 * class stays exactly as simple as its single-tenant-looking original
 * design (find the one active row) - multi-tenancy is handled entirely by
 * which schema the current connection is pointed at, not by an extra column
 * or a `WHERE tenant_id = ?` this entity would otherwise need everywhere.
 * See {@code com.agenticai.core.tenancy.TenantSchemaResolver}'s javadoc for
 * how that's wired in, and {@code TenantResolutionService} for how the
 * inbound webhook (which never gets a JWT-driven tenant context) resolves
 * which tenant's row this actually is.
 *
 * <p>{@code encryptedAuthToken} is ciphertext only (AES-256-GCM via the
 * existing {@link com.agenticai.core.crypto.EncryptionService} - the same
 * service LLM provider keys use, no separate crypto path needed). The
 * plaintext token is never persisted and this entity must never be
 * serialized directly in an API response - use
 * {@link com.agenticai.core.omnichannel.twilio.dto.TwilioCredentialResponse}.
 *
 * <p><b>API Key support (Twilio's own recommendation over the Auth Token
 * for everyday use)</b>: {@code apiKeySid}/{@code encryptedApiKeySecret} are
 * optional. When present, {@code WhatsAppDispatchService} authenticates
 * every OUTBOUND REST call (sending a message, the one-time activation
 * validation call) with the API Key instead of the Auth Token - an API Key
 * can be scoped and individually revoked/rotated without touching the rest
 * of the account, unlike the Auth Token, which grants full account access
 * and cannot be scoped at all. The Auth Token itself stays mandatory
 * regardless: Twilio computes the {@code X-Twilio-Signature} on every
 * INBOUND webhook call using the Account's Auth Token specifically (Twilio
 * offers no API-Key-based inbound signature scheme), so {@code
 * TwilioWebhookController} always needs it for that one purpose. The net
 * effect a tenant who adds an API Key actually gets: the Auth Token never
 * leaves this server again (it's used only to compute a local HMAC over an
 * inbound request, never sent to Twilio), while the credential that DOES
 * travel over the wire on every outbound call is the narrower, revocable
 * one - which is exactly the reduced-blast-radius outcome Twilio's own
 * guidance is describing.
 */
@Entity
@Table(name = "twilio_credential")
public class TwilioCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_sid", nullable = false, length = 64)
    private String accountSid;

    @Column(name = "encrypted_auth_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedAuthToken;

    @Column(name = "api_key_sid", length = 64)
    private String apiKeySid;

    @Column(name = "encrypted_api_key_secret", columnDefinition = "TEXT")
    private String encryptedApiKeySecret;

    @Column(name = "whatsapp_from_number", nullable = false, length = 32)
    private String whatsAppFromNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TwilioCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected TwilioCredential() {
        // JPA
    }

    public TwilioCredential(
        String accountSid, String encryptedAuthToken, String apiKeySid, String encryptedApiKeySecret, String whatsAppFromNumber
    ) {
        this.accountSid = accountSid;
        this.encryptedAuthToken = encryptedAuthToken;
        this.apiKeySid = apiKeySid;
        this.encryptedApiKeySecret = encryptedApiKeySecret;
        this.whatsAppFromNumber = whatsAppFromNumber;
        this.status = TwilioCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastValidatedAt = now;
    }

    public void reactivate(
        String accountSid, String encryptedAuthToken, String apiKeySid, String encryptedApiKeySecret, String whatsAppFromNumber
    ) {
        this.accountSid = accountSid;
        this.encryptedAuthToken = encryptedAuthToken;
        this.apiKeySid = apiKeySid;
        this.encryptedApiKeySecret = encryptedApiKeySecret;
        this.whatsAppFromNumber = whatsAppFromNumber;
        this.status = TwilioCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = Instant.now();
    }

    /** "Logout from Twilio" - see {@link TwilioCredentialStatus#DISCONNECTED}'s javadoc. */
    public void disconnect() {
        this.status = TwilioCredentialStatus.DISCONNECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getAccountSid() {
        return accountSid;
    }

    public String getEncryptedAuthToken() {
        return encryptedAuthToken;
    }

    public String getApiKeySid() {
        return apiKeySid;
    }

    public String getEncryptedApiKeySecret() {
        return encryptedApiKeySecret;
    }

    public boolean usesApiKey() {
        return apiKeySid != null && !apiKeySid.isBlank();
    }

    public String getWhatsAppFromNumber() {
        return whatsAppFromNumber;
    }

    public TwilioCredentialStatus getStatus() {
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
