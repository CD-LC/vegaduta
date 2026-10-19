package com.agenticai.core.omnichannel.discord;

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
 * A tenant's own Discord Application/Bot connection - BYOK, one active row per tenant schema,
 * ciphertext-only bot token, same shape as every other channel credential in this codebase.
 *
 * <p>Only one secret here (unlike Meta/Slack's two) - Discord has no separate "webhook signing
 * secret" concept for this platform's use case: outbound sends are a plain {@code Authorization:
 * Bot <token>} REST call (see {@link DiscordApiClient}), and inbound messages never arrive as a
 * public webhook Discord signs at all (see {@link DiscordWebhookController}'s javadoc for why
 * receiving requires a persistent Gateway connection instead) - so there's nothing analogous to
 * verify a signature against.
 */
@Entity
@Table(name = "discord_credential")
public class DiscordCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "application_id", nullable = false, length = 32)
    private String applicationId;

    @Column(name = "bot_username", length = 255)
    private String botUsername;

    @Column(name = "encrypted_bot_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedBotToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DiscordCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected DiscordCredential() {
        // JPA
    }

    public DiscordCredential(String applicationId, String botUsername, String encryptedBotToken) {
        this.applicationId = applicationId;
        this.botUsername = botUsername;
        this.encryptedBotToken = encryptedBotToken;
        this.status = DiscordCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastValidatedAt = now;
    }

    public void reactivate(String applicationId, String botUsername, String encryptedBotToken) {
        this.applicationId = applicationId;
        this.botUsername = botUsername;
        this.encryptedBotToken = encryptedBotToken;
        this.status = DiscordCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = Instant.now();
    }

    /**
     * Tenant-initiated disconnect - keeps the row so the console can still show what was connected;
     * every consumer resolves ACTIVE-only, so this alone stops all use.
     */
    public void disconnect() {
        this.status = DiscordCredentialStatus.DISCONNECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getBotUsername() {
        return botUsername;
    }

    public String getEncryptedBotToken() {
        return encryptedBotToken;
    }

    public DiscordCredentialStatus getStatus() {
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
