package com.agenticai.core.common.security;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Temporal user blacklist for abuse prevention.
 * Users are automatically or manually blocked due to detected fraud/abuse.
 *
 * Tenant-schema entity (lives in the calling tenant's Postgres schema).
 * Persisted via migration V358 ({@code user_blacklist}, {@code db/migration/tenant}).
 */
@Entity
@Table(name = "user_blacklist", indexes = {
    @Index(name = "idx_user_blacklist_lookup", columnList = "tenant_id,user_id,expires_at"),
    @Index(name = "idx_user_blacklist_expires", columnList = "expires_at")
})
public class UserBlacklist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID tenantId;

    /**
     * User ID to block
     */
    @Column(nullable = false)
    private UUID userId;

    /**
     * Reason for blocking: MULTIPLE_FAILED_LOGIN_ATTEMPTS, SUSPICIOUS_BILLING, MANUAL_BLOCK, etc.
     */
    @Column(nullable = false, length = 100)
    private String reason;

    /**
     * Who blocked this user: SYSTEM (automatic) or ADMIN_MANUAL (manually by admin)
     */
    @Column(nullable = false, length = 50)
    private String blockedBy;

    /**
     * Timestamp when the block expires (after which the user is unblocked)
     */
    @Column(nullable = false)
    private Instant expiresAt;

    /**
     * Timestamp when this block was created
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * Admin user ID if blocked by an admin
     */
    @Column
    private UUID createdBy;

    // Constructors
    public UserBlacklist() {
        this.createdAt = Instant.now();
    }

    public UserBlacklist(UUID tenantId, UUID userId, String reason, String blockedBy, Instant expiresAt) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.reason = reason;
        this.blockedBy = blockedBy;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getBlockedBy() { return blockedBy; }
    public void setBlockedBy(String blockedBy) { this.blockedBy = blockedBy; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public boolean isActive() {
        return Instant.now().isBefore(expiresAt);
    }

    @Override
    public String toString() {
        return "UserBlacklist{" +
                "id=" + id +
                ", tenantId=" + tenantId +
                ", userId=" + userId +
                ", reason='" + reason + '\'' +
                ", blockedBy='" + blockedBy + '\'' +
                ", expiresAt=" + expiresAt +
                ", createdAt=" + createdAt +
                '}';
    }
}
