package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A tenant's connection to its own BlueBubbles server (the macOS bridge that exposes Messages
 * over REST) - BYOK, one row per tenant schema, ciphertext-only password and webhook secret.
 *
 * <p>{@code webhookId} is public (it is in the webhook URL and in the cross-tenant account link);
 * the webhook call is authenticated by the secret, never by knowing the id.
 */
@Entity
@Table(name = "imessage_credential")
public class ImessageCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "webhook_id", nullable = false, length = 48)
    private String webhookId;

    @Column(name = "server_url", nullable = false, length = 512)
    private String serverUrl;

    @Column(name = "encrypted_password", nullable = false, columnDefinition = "TEXT")
    private String encryptedPassword;

    @Column(name = "encrypted_webhook_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedWebhookSecret;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ImessageCredentialStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "inbound_mode", nullable = false, length = 16)
    private ImessageInboundMode inboundMode;

    @Column(name = "allowed_senders", nullable = false, columnDefinition = "TEXT")
    private String allowedSenders;

    @Column(name = "allow_groups", nullable = false)
    private boolean allowGroups;

    @Enumerated(EnumType.STRING)
    @Column(name = "unknown_sender_policy", nullable = false, length = 16)
    private UnknownSenderPolicy unknownSenderPolicy;

    @Column(name = "poll_cursor_ms")
    private Long pollCursorMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected ImessageCredential() {
        // JPA
    }

    public ImessageCredential(
            String webhookId,
            String serverUrl,
            String encryptedPassword,
            String encryptedWebhookSecret,
            ImessageInboundMode inboundMode) {
        this.webhookId = webhookId;
        this.serverUrl = serverUrl;
        this.encryptedPassword = encryptedPassword;
        this.encryptedWebhookSecret = encryptedWebhookSecret;
        this.status = ImessageCredentialStatus.ACTIVE;
        this.inboundMode = inboundMode;
        this.allowedSenders = "";
        this.allowGroups = false;
        this.unknownSenderPolicy = UnknownSenderPolicy.PAIR;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastValidatedAt = now;
    }

    /** Re-connect: new server details and a fresh webhook secret; the allow-list and policy are kept. */
    public void reactivate(
            String webhookId,
            String serverUrl,
            String encryptedPassword,
            String encryptedWebhookSecret,
            ImessageInboundMode inboundMode) {
        this.webhookId = webhookId;
        this.serverUrl = serverUrl;
        this.encryptedPassword = encryptedPassword;
        this.encryptedWebhookSecret = encryptedWebhookSecret;
        this.inboundMode = inboundMode;
        this.status = ImessageCredentialStatus.ACTIVE;
        this.pollCursorMs = null;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = this.updatedAt;
    }

    /** Tenant-initiated disconnect - keeps the row so the console can still show what was connected. */
    public void disconnect(String encryptedBlankPassword, String encryptedUnusedSecret) {
        this.status = ImessageCredentialStatus.DISCONNECTED;
        this.encryptedPassword = encryptedBlankPassword;
        this.encryptedWebhookSecret = encryptedUnusedSecret;
        this.pollCursorMs = null;
        this.updatedAt = Instant.now();
    }

    public void updateSettings(
            ImessageInboundMode inboundMode,
            Set<String> allowedSenders,
            boolean allowGroups,
            UnknownSenderPolicy unknownSenderPolicy) {
        if (this.inboundMode != inboundMode) {
            this.pollCursorMs = null;
        }
        this.inboundMode = inboundMode;
        this.allowedSenders = ImessageAddress.serialize(allowedSenders);
        this.allowGroups = allowGroups;
        this.unknownSenderPolicy = unknownSenderPolicy;
        this.updatedAt = Instant.now();
    }

    public void advancePollCursor(long cursorMs) {
        this.pollCursorMs = cursorMs;
    }

    public UUID getId() {
        return id;
    }

    public String getWebhookId() {
        return webhookId;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getEncryptedPassword() {
        return encryptedPassword;
    }

    public String getEncryptedWebhookSecret() {
        return encryptedWebhookSecret;
    }

    public ImessageCredentialStatus getStatus() {
        return status;
    }

    public ImessageInboundMode getInboundMode() {
        return inboundMode;
    }

    public Set<String> allowedSenders() {
        return ImessageAddress.parseList(allowedSenders);
    }

    public boolean isAllowGroups() {
        return allowGroups;
    }

    public UnknownSenderPolicy getUnknownSenderPolicy() {
        return unknownSenderPolicy;
    }

    public Long getPollCursorMs() {
        return pollCursorMs;
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
