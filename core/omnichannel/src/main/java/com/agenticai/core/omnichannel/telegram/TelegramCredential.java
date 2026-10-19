package com.agenticai.core.omnichannel.telegram;

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
 * A tenant's own Telegram Bot connection - BYOK, one active row per tenant
 * schema, ciphertext-only bot token, same shape as {@code DiscordCredential}.
 *
 * <p>Unlike Discord, Telegram's Bot API has a real public webhook (no
 * persistent Gateway connection needed) - see {@link
 * com.agenticai.core.omnichannel.telegram.TelegramWebhookController}. {@code
 * webhookSecret} is a platform-generated opaque token (not something the
 * tenant types in) that Telegram echoes back on every webhook POST via the
 * {@code X-Telegram-Bot-Api-Secret-Token} header - it doesn't need
 * encryption at rest the way the bot token does, since knowing it alone
 * only lets someone POST fake Telegram-shaped payloads, not act as the bot.
 */
@Entity
@Table(name = "telegram_credential")
public class TelegramCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bot_user_id", nullable = false, length = 32)
    private String botUserId;

    @Column(name = "bot_username", length = 255)
    private String botUsername;

    @Column(name = "encrypted_bot_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedBotToken;

    @Column(name = "webhook_secret", nullable = false, length = 64)
    private String webhookSecret;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TelegramCredentialStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected TelegramCredential() {
        // JPA
    }

    public TelegramCredential(String botUserId, String botUsername, String encryptedBotToken, String webhookSecret) {
        this.botUserId = botUserId;
        this.botUsername = botUsername;
        this.encryptedBotToken = encryptedBotToken;
        this.webhookSecret = webhookSecret;
        this.status = TelegramCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
        this.lastValidatedAt = now;
    }

    public void reactivate(String botUserId, String botUsername, String encryptedBotToken, String webhookSecret) {
        this.botUserId = botUserId;
        this.botUsername = botUsername;
        this.encryptedBotToken = encryptedBotToken;
        this.webhookSecret = webhookSecret;
        this.status = TelegramCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = Instant.now();
    }

    /** Tenant-initiated disconnect - keeps the row so the console can still show what was connected; every consumer resolves ACTIVE-only, so this alone stops all use. */
    public void disconnect() {
        this.status = TelegramCredentialStatus.DISCONNECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getBotUserId() {
        return botUserId;
    }

    public String getBotUsername() {
        return botUsername;
    }

    public String getEncryptedBotToken() {
        return encryptedBotToken;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public TelegramCredentialStatus getStatus() {
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
