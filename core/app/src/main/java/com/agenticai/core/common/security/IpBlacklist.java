package com.agenticai.core.common.security;

import jakarta.persistence.*;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * Temporal IP blacklist for abuse prevention.
 * IPs are automatically or manually blocked due to detected fraud/abuse.
 *
 * Tenant-schema entity (lives in the calling tenant's Postgres schema).
 * Persisted via migration V357 ({@code ip_blacklist}, {@code db/migration/tenant}).
 */
@Entity
@Table(name = "ip_blacklist", indexes = {
    @Index(name = "idx_ip_blacklist_lookup", columnList = "tenant_id,ip_address,expires_at"),
    @Index(name = "idx_ip_blacklist_expires", columnList = "expires_at")
})
public class IpBlacklist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID tenantId;

    /**
     * IP address to block (INET type in Postgres)
     */
    @Column(nullable = false)
    private InetAddress ipAddress;

    /**
     * Reason for blocking: AUTH_BRUTE_FORCE_DETECTED, RATE_LIMIT_EXCEEDED, SUSPICIOUS_PATTERN, MANUAL_BLOCK, etc.
     */
    @Column(nullable = false, length = 100)
    private String reason;

    /**
     * Who blocked this IP: SYSTEM (automatic) or ADMIN_MANUAL (manually by admin)
     */
    @Column(nullable = false, length = 50)
    private String blockedBy;

    /**
     * Timestamp when the block expires (after which the IP is unblocked)
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
    public IpBlacklist() {
        this.createdAt = Instant.now();
    }

    public IpBlacklist(UUID tenantId, InetAddress ipAddress, String reason, String blockedBy, Instant expiresAt) {
        this.tenantId = tenantId;
        this.ipAddress = ipAddress;
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

    public InetAddress getIpAddress() { return ipAddress; }
    public void setIpAddress(InetAddress ipAddress) { this.ipAddress = ipAddress; }

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
        return "IpBlacklist{" +
                "id=" + id +
                ", tenantId=" + tenantId +
                ", ipAddress=" + ipAddress +
                ", reason='" + reason + '\'' +
                ", blockedBy='" + blockedBy + '\'' +
                ", expiresAt=" + expiresAt +
                ", createdAt=" + createdAt +
                '}';
    }
}
