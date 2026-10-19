package com.agenticai.core.omnichannel.slack;

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
 * A tenant's own Slack App connection - BYOK, same reasoning as every other channel credential in
 * this package (one active row per tenant schema, ciphertext-only secrets). Two secrets for two
 * different jobs, same split as {@link com.agenticai.core.omnichannel.meta.MetaWhatsAppCredential}:
 * {@code encryptedBotToken} (Bearer token on every outbound {@code chat.postMessage} call) and
 * {@code encryptedSigningSecret} (HMAC key for verifying inbound {@code X-Slack-Signature} - see
 * {@link SlackSignatureValidator}, Slack's own scheme, distinct from Meta's {@code
 * X-Hub-Signature-256}).
 *
 * <p>{@code botUserId} is stored specifically to solve a Slack-specific problem neither WhatsApp
 * nor Messenger has: Slack's Events API delivers EVERY message in a channel the bot is in,
 * including the bot's own replies - without filtering those out by comparing the event's {@code
 * user} field against this bot's own user ID, the bot would reply to itself in an infinite loop.
 * See {@code SlackWebhookController#handleEvent}.
 */
@Entity
@Table(name = "slack_credential")
public class SlackCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "team_id", nullable = false, length = 32)
    private String teamId;

    @Column(name = "team_name", length = 255)
    private String teamName;

    @Column(name = "bot_user_id", nullable = false, length = 32)
    private String botUserId;

    @Column(name = "encrypted_bot_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedBotToken;

    @Column(name = "encrypted_signing_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedSigningSecret;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SlackCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected SlackCredential() {
        // JPA
    }

    public SlackCredential(
            String teamId,
            String teamName,
            String botUserId,
            String encryptedBotToken,
            String encryptedSigningSecret) {
        this.teamId = teamId;
        this.teamName = teamName;
        this.botUserId = botUserId;
        this.encryptedBotToken = encryptedBotToken;
        this.encryptedSigningSecret = encryptedSigningSecret;
        this.status = SlackCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastValidatedAt = now;
    }

    public void reactivate(
            String teamId,
            String teamName,
            String botUserId,
            String encryptedBotToken,
            String encryptedSigningSecret) {
        this.teamId = teamId;
        this.teamName = teamName;
        this.botUserId = botUserId;
        this.encryptedBotToken = encryptedBotToken;
        this.encryptedSigningSecret = encryptedSigningSecret;
        this.status = SlackCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = Instant.now();
    }

    /**
     * Tenant-initiated disconnect - keeps the row so the console can still show what was connected;
     * every consumer resolves ACTIVE-only, so this alone stops all use.
     */
    public void disconnect() {
        this.status = SlackCredentialStatus.DISCONNECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTeamId() {
        return teamId;
    }

    public String getTeamName() {
        return teamName;
    }

    public String getBotUserId() {
        return botUserId;
    }

    public String getEncryptedBotToken() {
        return encryptedBotToken;
    }

    public String getEncryptedSigningSecret() {
        return encryptedSigningSecret;
    }

    public SlackCredentialStatus getStatus() {
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
