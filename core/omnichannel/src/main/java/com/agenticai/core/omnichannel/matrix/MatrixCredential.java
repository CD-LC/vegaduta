package com.agenticai.core.omnichannel.matrix;

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
 * A tenant's own Matrix bot connection: BYOK, one row per tenant schema, ciphertext-only access
 * token. The bot is an ordinary user on a homeserver the operator chose; {@code botUserId} is its
 * MXID as the homeserver reported it ({@code whoami}), never what the tenant typed.
 *
 * <p>{@code sinceToken} is the {@code /sync} {@code next_batch} of the last fully processed batch.
 * It is deliberately on this row, not in memory, so a restart resumes instead of replaying history.
 */
@Entity
@Table(name = "matrix_credential")
public class MatrixCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "homeserver_url", nullable = false, length = 512)
    private String homeserverUrl;

    @Column(name = "bot_user_id", nullable = false, length = 255)
    private String botUserId;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(name = "encrypted_access_token", nullable = false, columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MatrixCredentialStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "join_policy", nullable = false, length = 16)
    private MatrixJoinPolicy joinPolicy;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_mode", nullable = false, length = 16)
    private MatrixSenderMode senderMode;

    @Column(name = "allowed_senders", nullable = false, columnDefinition = "TEXT")
    private String allowedSenders;

    @Column(name = "since_token", columnDefinition = "TEXT")
    private String sinceToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_validated_at")
    private Instant lastValidatedAt;

    protected MatrixCredential() {
        // JPA
    }

    public MatrixCredential(
            String homeserverUrl,
            String botUserId,
            String deviceId,
            String encryptedAccessToken,
            MatrixJoinPolicy joinPolicy,
            MatrixSenderMode senderMode,
            String allowedSenders) {
        this.status = MatrixCredentialStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        reconnect(
                homeserverUrl,
                botUserId,
                deviceId,
                encryptedAccessToken,
                joinPolicy,
                senderMode,
                allowedSenders);
    }

    /** Replaces the connection. The since token is dropped: it belongs to the old account. */
    public void reconnect(
            String homeserverUrl,
            String botUserId,
            String deviceId,
            String encryptedAccessToken,
            MatrixJoinPolicy joinPolicy,
            MatrixSenderMode senderMode,
            String allowedSenders) {
        this.homeserverUrl = homeserverUrl;
        this.botUserId = botUserId;
        this.deviceId = deviceId;
        this.encryptedAccessToken = encryptedAccessToken;
        this.joinPolicy = joinPolicy;
        this.senderMode = senderMode;
        this.allowedSenders = allowedSenders == null ? "" : allowedSenders;
        this.sinceToken = null;
        this.status = MatrixCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.lastValidatedAt = this.updatedAt;
    }

    public void updatePolicy(
            MatrixJoinPolicy joinPolicy, MatrixSenderMode senderMode, String allowedSenders) {
        this.joinPolicy = joinPolicy;
        this.senderMode = senderMode;
        this.allowedSenders = allowedSenders == null ? "" : allowedSenders;
        this.updatedAt = Instant.now();
    }

    public void updateSinceToken(String sinceToken) {
        this.sinceToken = sinceToken;
    }

    /** The homeserver refused the token: stop using it until the tenant reconnects. */
    public void markInvalid() {
        this.status = MatrixCredentialStatus.INVALID;
        this.updatedAt = Instant.now();
    }

    /** Tenant-initiated disconnect. The row stays so the console can say what was connected. */
    public void disconnect() {
        this.status = MatrixCredentialStatus.DISCONNECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getHomeserverUrl() {
        return homeserverUrl;
    }

    public String getBotUserId() {
        return botUserId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getEncryptedAccessToken() {
        return encryptedAccessToken;
    }

    public MatrixCredentialStatus getStatus() {
        return status;
    }

    public MatrixJoinPolicy getJoinPolicy() {
        return joinPolicy;
    }

    public MatrixSenderMode getSenderMode() {
        return senderMode;
    }

    public String getAllowedSenders() {
        return allowedSenders;
    }

    public String getSinceToken() {
        return sinceToken;
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
