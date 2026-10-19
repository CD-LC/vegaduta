package com.agenticai.core.omnichannel.teams;

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
 * A tenant's own Azure Bot registration for the Microsoft Teams channel - BYOK, one active row per
 * tenant schema, ciphertext-only app password, same shape as {@code TelegramCredential}. {@code
 * appId} is the Microsoft App ID of the bot registration; {@code aadTenantId} is the Entra tenant
 * tokens are minted against - {@code "botframework.com"} for multi-tenant registrations, the
 * tenant's own GUID for single-tenant ones.
 *
 * <p>Unlike Telegram there is no per-tenant webhook secret to register: Microsoft's Bot Framework
 * POSTs activities to ONE operator-configured messaging endpoint ({@code /webhook/teams/{appId}})
 * and authenticates each call with a JWT signed by {@code login.botframework.com} whose audience is
 * the appId itself - verified in {@link TeamsWebhookController}. The route table that makes replies
 * possible ({@code teams_conversation_route}) is a separate entity - the Bot Framework serviceUrl
 * is per-conversation, not per-bot.
 */
@Entity
@Table(name = "teams_credential")
public class TeamsCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    @Column(name = "aad_tenant_id", nullable = false, length = 64)
    private String aadTenantId;

    @Column(name = "encrypted_app_password", nullable = false, columnDefinition = "TEXT")
    private String encryptedAppPassword;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TeamsCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected TeamsCredential() {
        // JPA
    }

    public TeamsCredential(String appId, String aadTenantId, String encryptedAppPassword) {
        this.appId = appId;
        this.aadTenantId = aadTenantId;
        this.encryptedAppPassword = encryptedAppPassword;
        this.status = TeamsCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastValidatedAt = now;
    }

    public void reactivate(String appId, String aadTenantId, String encryptedAppPassword) {
        this.appId = appId;
        this.aadTenantId = aadTenantId;
        this.encryptedAppPassword = encryptedAppPassword;
        this.status = TeamsCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = Instant.now();
    }

    /**
     * Tenant-initiated disconnect - keeps the row so the console can still show what was connected;
     * every consumer resolves ACTIVE-only, so this alone stops all use.
     */
    public void disconnect() {
        this.status = TeamsCredentialStatus.DISCONNECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getAppId() {
        return appId;
    }

    public String getAadTenantId() {
        return aadTenantId;
    }

    public String getEncryptedAppPassword() {
        return encryptedAppPassword;
    }

    public TeamsCredentialStatus getStatus() {
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
